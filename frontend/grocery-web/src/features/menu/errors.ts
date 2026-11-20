import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'

/** Message for menu errors every menu action can hit. */
export function menuErrorMessage(error: unknown): string {
  if (hasErrorCode(error, 'RESTAURANT_SUSPENDED')) return 'Your restaurant is suspended, so the menu cannot be changed right now.'
  if (hasErrorCode(error, 'RESTAURANT_NOT_FOUND')) return 'Your restaurant profile could not be found. Reload the page and try again.'
  if (hasErrorCode(error, 'MENU_CATEGORY_NOT_EMPTY')) {
    return 'This category still has dishes. Move them to another category or delete them first — only empty categories can be deleted.'
  }
  if (hasErrorCode(error, 'MENU_ITEM_NOT_FOUND', 'MENU_CATEGORY_NOT_FOUND')) return 'It was already removed. The menu has been refreshed.'
  return errorMessage(error)
}
