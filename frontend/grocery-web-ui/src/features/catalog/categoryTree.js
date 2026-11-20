/**
 * Any category tree node (public `CategoryNode`, admin `AdminCategoryNode`).
 * @template T
 * @typedef {object} TreeNode
 * @property {string} id
 * @property {string} name
 * @property {T[]} children
 */

/**
 * @template T
 * @typedef {object} FlatNode
 * @property {T} node
 * @property {number} depth 1 = top level.
 * @property {string[]} path Names root → this node.
 */

/**
 * Depth-first list of the tree in display order, with depth and name path (for indented selects and tables).
 * @template {TreeNode<T>} T
 * @param {readonly T[]} roots
 * @returns {FlatNode<T>[]}
 */
export function flattenTree(roots) {
  const result = []
  const visit = (nodes, depth, parentPath) => {
    for (const node of nodes) {
      const path = [...parentPath, node.name]
      result.push({ node, depth, path })
      visit(node.children, depth + 1, path)
    }
  }
  visit(roots, 1, [])
  return result
}

/**
 * @template {TreeNode<T>} T
 * @param {readonly T[]} roots
 * @param {string} id
 * @returns {T | null}
 */
export function findNode(roots, id) {
  for (const node of roots) {
    if (node.id === id) return node
    const found = findNode(node.children, id)
    if (found) return found
  }
  return null
}

/**
 * Ids of `node` and every descendant.
 * @template {TreeNode<T>} T
 * @param {T} node
 * @returns {Set<string>}
 */
export function subtreeIds(node) {
  const ids = new Set([node.id])
  const visit = (children) => {
    for (const child of children) {
      ids.add(child.id)
      visit(child.children)
    }
  }
  visit(node.children)
  return ids
}

/**
 * Levels in the subtree rooted at `node` (a leaf is 1).
 * @template {TreeNode<T>} T
 * @param {T} node
 * @returns {number}
 */
export function subtreeHeight(node) {
  return 1 + Math.max(0, ...node.children.map((child) => subtreeHeight(child)))
}

/**
 * Option label for indented `<select>`s: non-breaking spaces (kept by `<option>`) per level below the top.
 * @param {string} name
 * @param {number} depth
 * @returns {string}
 */
export function indentLabel(name, depth) {
  return `${'\u00A0\u00A0\u00A0'.repeat(Math.max(depth - 1, 0))}${depth > 1 ? '└ ' : ''}${name}`
}

export const PATH_SEPARATOR = ' › '
