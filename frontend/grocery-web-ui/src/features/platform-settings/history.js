import { formatSetting } from './fields'

/** @typedef {import('./api').SettingChange} SettingChange */
/** @typedef {import('./fields').AreaDefinition} AreaDefinition */
/** @typedef {import('./fields').SettingField} SettingField */

/**
 * A field's change as the history shows it: its label, and both values with their unit.
 * @typedef {object} ChangeLine
 * @property {string} field
 * @property {string} label "Partners offered per wave"; a fee names its group too: "Grocery base fee".
 * @property {string | null} from "2 partners", "₹40", "3, 6, 10 km"; null = the default.
 * @property {string | null} to
 */

/**
 * What one save changed: when, who, and its fields in the card's order.
 * @typedef {object} SavedChanges
 * @property {string} key
 * @property {string} changedAt
 * @property {string} by The admin's name (or email) when they saved.
 * @property {ChangeLine[]} lines
 */

/**
 * @param {AreaDefinition} definition
 * @param {string} key
 * @returns {SettingField | undefined}
 */
function fieldOf(definition, key) {
  return definition.fields.find((field) => field.key === key)
}

/**
 * The field's place on the card; a field the page does not know goes last.
 * @param {AreaDefinition} definition
 * @param {string} key
 * @returns {number}
 */
function fieldIndex(definition, key) {
  const index = definition.fields.findIndex((field) => field.key === key)
  return index === -1 ? definition.fields.length : index
}

/**
 * @param {SettingField | undefined} field
 * @param {SettingChange['oldValue']} value
 * @returns {string | null}
 */
function valueText(field, value) {
  if (value === null || value === undefined) return null
  // A value stored by hand that the service could not read comes as its text: shown as it is.
  if (typeof value === 'string') return value
  if (!field) return Array.isArray(value) ? value.join(', ') : String(value)
  return formatSetting(field, value)
}

/**
 * @param {AreaDefinition} definition
 * @param {SettingChange} change
 * @returns {ChangeLine}
 */
export function changeLine(definition, change) {
  const field = fieldOf(definition, change.field)
  let label = change.field
  if (field) label = field.group ? `${field.group} ${field.label.toLowerCase()}` : field.label
  return { field: change.field, label, from: valueText(field, change.oldValue), to: valueText(field, change.newValue) }
}

/**
 * One page of history (newest first) as saves. The rows one save writes share its time and admin, so neighbours with
 * both equal belong together; their fields are listed in the card's order (the service sends a save's rows in the
 * reverse order of writing). A save cut by a page break shows on both pages.
 * @param {AreaDefinition} definition
 * @param {readonly SettingChange[]} changes
 * @returns {SavedChanges[]}
 */
export function groupBySave(definition, changes) {
  const saves = []
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
