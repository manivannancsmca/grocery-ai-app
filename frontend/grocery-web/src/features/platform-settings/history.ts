import type { SettingChange } from './api'
import { type AreaDefinition, formatSetting, type SettingField } from './fields'

/** A field's change as the history shows it: its label, and both values with their unit. */
export interface ChangeLine {
  field: string
  /** "Partners offered per wave"; a fee names its group too: "Grocery base fee". */
  label: string
  /** "2 partners", "₹40", "3, 6, 10 km"; null = the default. */
  from: string | null
  to: string | null
}

/** What one save changed: when, who, and its fields in the card's order. */
export interface SavedChanges {
  key: string
  changedAt: string
  /** The admin's name (or email) when they saved. */
  by: string
  lines: ChangeLine[]
}

function fieldOf(definition: AreaDefinition, key: string): SettingField | undefined {
  return definition.fields.find((field) => field.key === key)
}

/** The field's place on the card; a field the page does not know goes last. */
function fieldIndex(definition: AreaDefinition, key: string): number {
  const index = definition.fields.findIndex((field) => field.key === key)
  return index === -1 ? definition.fields.length : index
}

function valueText(field: SettingField | undefined, value: SettingChange['oldValue']): string | null {
  if (value === null || value === undefined) return null
  // A value stored by hand that the service could not read comes as its text: shown as it is.
  if (typeof value === 'string') return value
  if (!field) return Array.isArray(value) ? value.join(', ') : String(value)
  return formatSetting(field, value)
}

export function changeLine(definition: AreaDefinition, change: SettingChange): ChangeLine {
  const field = fieldOf(definition, change.field)
  let label = change.field
  if (field) label = field.group ? `${field.group} ${field.label.toLowerCase()}` : field.label
  return { field: change.field, label, from: valueText(field, change.oldValue), to: valueText(field, change.newValue) }
}

/**
 * One page of history (newest first) as saves. The rows one save writes share its time and admin, so neighbours with
 * both equal belong together; their fields are listed in the card's order (the service sends a save's rows in the
 * reverse order of writing). A save cut by a page break shows on both pages.
 */
export function groupBySave(definition: AreaDefinition, changes: readonly SettingChange[]): SavedChanges[] {
  const saves: { save: SavedChanges; adminId: string }[] = []
  for (const change of changes) {
    const last = saves.at(-1)
    if (last && last.save.changedAt === change.changedAt && last.adminId === change.changedBy.id) {
      last.save.lines.push(changeLine(definition, change))
      continue
    }
    saves.push({
      adminId: change.changedBy.id,
      save: {
        key: `${change.changedAt}|${change.changedBy.id}|${saves.length}`,
        changedAt: change.changedAt,
        by: change.changedBy.name?.trim() || 'An admin',
        lines: [changeLine(definition, change)],
      },
    })
  }
  return saves.map(({ save }) => ({
    ...save,
    lines: save.lines.toSorted((a, b) => fieldIndex(definition, a.field) - fieldIndex(definition, b.field)),
  }))
}
