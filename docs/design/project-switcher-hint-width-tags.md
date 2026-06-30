# ProjectSwitcher Dropdown-Hinweis-Breite — testTags (CYP-159, Klasse C)

> docs-only · Grounded @ develop `d57dccb`. Geteilter QA-Vertrag mit CYP-7 — nie still umbenennen.

## Neue Tags: **KEINE**

Reine Breitenschranke (`widthIn`) am bestehenden Hinweis-Knoten — **kein** neuer Knoten, **kein** neuer Tag.

## Reuse — unverändert
| Knoten | Tag | Quelle |
|---|---|---|
| Switch-Hinweis (umbricht jetzt) | `ProjectTags.SWITCH_HINT` | bestehend |
| Switcher-Leiste | `ProjectTags.BAR` | bestehend |
| Aktiv-Anzeige | `ProjectTags.ACTIVE` | bestehend |
| Menü / Menü-Items | `ProjectTags.MENU`, `ProjectTags.item(<id>)`, `ProjectTags.MANAGE` | bestehend |
| Scope-Hinweis | `ProjectTags.SCOPE_HINT` | bestehend |

- **a11y:** Hinweis-Text + Menü-`contentDescription` unverändert.
