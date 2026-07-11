// Types for contractInput.mjs (consumed by the codegen script at runtime and the vitest unit test for types).
export function selectContractInput(realExists: boolean, requireReal: boolean): 'real' | 'provisional'
export function contractRequireReal(envValue: string | undefined): boolean
