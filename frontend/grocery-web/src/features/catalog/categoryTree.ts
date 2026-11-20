/** Any category tree node (public `CategoryNode`, admin `AdminCategoryNode`). */
export interface TreeNode<T> {
  id: string
  name: string
  children: T[]
}

export interface FlatNode<T> {
  node: T
  /** 1 = top level. */
  depth: number
  /** Names root → this node. */
  path: string[]
}

/** Depth-first list of the tree in display order, with depth and name path (for indented selects and tables). */
export function flattenTree<T extends TreeNode<T>>(roots: readonly T[]): FlatNode<T>[] {
  const result: FlatNode<T>[] = []
  const visit = (nodes: readonly T[], depth: number, parentPath: string[]) => {
    for (const node of nodes) {
      const path = [...parentPath, node.name]
      result.push({ node, depth, path })
      visit(node.children, depth + 1, path)
    }
  }
  visit(roots, 1, [])
  return result
}

export function findNode<T extends TreeNode<T>>(roots: readonly T[], id: string): T | null {
  for (const node of roots) {
    if (node.id === id) return node
    const found = findNode(node.children, id)
    if (found) return found
  }
  return null
}

/** Ids of `node` and every descendant. */
export function subtreeIds<T extends TreeNode<T>>(node: T): Set<string> {
  const ids = new Set<string>([node.id])
  const visit = (children: readonly T[]) => {
    for (const child of children) {
      ids.add(child.id)
      visit(child.children)
    }
  }
  visit(node.children)
  return ids
}

/** Levels in the subtree rooted at `node` (a leaf is 1). */
export function subtreeHeight<T extends TreeNode<T>>(node: T): number {
  return 1 + Math.max(0, ...node.children.map((child) => subtreeHeight(child)))
}

/** Option label for indented `<select>`s: non-breaking spaces (kept by `<option>`) per level below the top. */
export function indentLabel(name: string, depth: number): string {
  return `${'\u00A0\u00A0\u00A0'.repeat(Math.max(depth - 1, 0))}${depth > 1 ? '└ ' : ''}${name}`
}

export const PATH_SEPARATOR = ' › '
