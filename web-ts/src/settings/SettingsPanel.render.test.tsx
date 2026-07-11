// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act } from '@testing-library/react'
import { SettingsPanel, type SettingsPanelProps } from './SettingsPanel'
import { RestError } from '../net/rest'
import type { RepoConfigView } from '../types/generated/contract'

const configured: RepoConfigView = { configured: true, url: 'git@github.com:o/r.git', branch: 'main', reprovisionPending: false }

const rejectWith = (code: string) =>
  new RestError(400, 'PUT', '/api/config/repo', JSON.stringify({ error: { code, message: 'x' } }))

const renderPanel = (over: Partial<SettingsPanelProps> = {}) => {
  const props: SettingsPanelProps = {
    operator: true,
    repoConfig: configured,
    onSaveRepo: vi.fn().mockResolvedValue(undefined),
    apiKeyView: { set: true, masked: '***k999' },
    onSaveApiKey: vi.fn().mockResolvedValue(undefined),
    ...over,
  }
  return { props, ...render(<SettingsPanel {...props} />) }
}

afterEach(cleanup)

describe('SettingsPanel (CYP-453)', () => {
  it('frames the API-key section (CYP-433), does not duplicate it (tooth 6)', () => {
    const { getByTestId } = renderPanel()
    expect(getByTestId('settings.section.repo')).toBeTruthy()
    expect(getByTestId('settings.section.apiKey')).toBeTruthy() // rendered by the framed CYP-433 ApiKeyPanel
    expect(getByTestId('settings.apiKey.masked').textContent).toContain('***k999')
  })

  it('the API-key effect-hint has exactly ONE render source (framed CYP-433), never a 2nd P2-f node (§9.6, tooth 6)', async () => {
    const { getByTestId, findAllByTestId } = renderPanel()
    // trigger the framed ApiKeyPanel's own effect-hint (its save flow) …
    fireEvent.change(getByTestId('settings.apiKey.input'), { target: { value: 'sk-newkey-123456' } })
    await act(async () => {
      fireEvent.click(getByTestId('settings.apiKey.save'))
    })
    // … and assert it appears exactly once — a P2-f second instance would be a duplicate testid (forbidden).
    expect(await findAllByTestId('settings.apiKey.effectHint')).toHaveLength(1)
  })

  it('project config is operator-gated present-but-disabled — inputs present but disabled + gate hint (teeth 2/8)', () => {
    const { getByTestId } = renderPanel({ operator: false })
    // present, not omitted:
    expect(getByTestId('settings.repo.url.input')).toBeTruthy()
    expect((getByTestId('settings.repo.url.input') as HTMLInputElement).disabled).toBe(true)
    expect((getByTestId('settings.repo.branch.input') as HTMLInputElement).disabled).toBe(true)
    expect((getByTestId('settings.repo.save') as HTMLButtonElement).disabled).toBe(true)
    expect(getByTestId('settings.repo.gateHint')).toBeTruthy()
  })

  it('repo-unset is honest — names the consequence, not silently empty (tooth 5)', () => {
    const { getByTestId } = renderPanel({ repoConfig: { configured: false, url: null, branch: null, reprovisionPending: false } })
    expect(getByTestId('settings.repo.status').textContent).toContain('Agenten können nicht starten')
  })

  it('a configured repo shows no unset status', () => {
    const { queryByTestId } = renderPanel()
    expect(queryByTestId('settings.repo.status')).toBeNull()
  })

  it('on save success shows the AMBER "saved ≠ active" effect-hint (deferred), no restart control (teeth 3/4/7)', async () => {
    const { getByTestId, findByTestId, queryByTestId } = renderPanel()
    await act(async () => {
      fireEvent.click(getByTestId('settings.repo.save'))
    })
    const hint = await findByTestId('settings.repo.effectHint')
    expect(hint.textContent).toContain('bestehende Worktrees bleiben unverändert') // deferred, not "active now"
    expect(hint.className).toContain('effect-hint') // amber styling hook, never success-green
    // no restart control lives in the settings level (the hint points at the P2-a restart)
    expect(queryByTestId('settings.repo.restart')).toBeNull()
  })

  it('a server reject (invalid_repo_url) surfaces on the error line, no effect-hint (server-authoritative)', async () => {
    const onSaveRepo = vi.fn().mockRejectedValue(rejectWith('invalid_repo_url'))
    const { getByTestId, findByTestId, queryByTestId } = renderPanel({ onSaveRepo })
    fireEvent.change(getByTestId('settings.repo.url.input'), { target: { value: 'not a url' } })
    await act(async () => {
      fireEvent.click(getByTestId('settings.repo.save'))
    })
    expect((await findByTestId('settings.repo.error')).textContent).toContain('Ungültige Repository-URL')
    expect(queryByTestId('settings.repo.effectHint')).toBeNull() // no faked "saved" on a reject
  })
})
