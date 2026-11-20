import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSettings, fetchSettingsHistory, saveSettings, settingsKeys } from './api'
import { optimisticView } from './fields'

/** @typedef {import('./api').SettingsArea} SettingsArea */

/**
 * One area's settings. Each card has its own query, so one service being down leaves the other cards working.
 * @param {SettingsArea} area
 */
export function useAreaSettings(area) {
  return useQuery({ queryKey: settingsKeys.area(area), queryFn: () => fetchSettings(area) })
}

/**
 * Saves one area. Optimistic: the card shows the new values at once and goes back to the previous view when the save
 * fails. On success the server's view (new version, who changed it) replaces the guess. A 409 is not refetched
 * automatically: the admin's edits stay until they reload, so another admin's change is never overwritten unseen.
 * @param {SettingsArea} area
 */
export function useSaveAreaSettings(area) {
  const queryClient = useQueryClient()
  const queryKey = settingsKeys.area(area)
  return useMutation({
    mutationFn: (update) => saveSettings(area, update),
    onMutate: async (update) => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData(queryKey)
      if (previous) queryClient.setQueryData(queryKey, optimisticView(previous, update))
      return { previous }
    },
    onError: (_error, _update, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous)
    },
    onSuccess: (view) => {
      queryClient.setQueryData(queryKey, view)
      // The save added rows to the area's history.
      void queryClient.invalidateQueries({ queryKey: settingsKeys.history(area) })
    },
  })
}

/**
 * One page of an area's change history, newest first; the page on screen stays while the next one loads.
 * @param {SettingsArea} area
 * @param {number} page
 * @param {number} size
 */
export function useAreaHistory(area, page, size) {
  return useQuery({
    queryKey: settingsKeys.historyPage(area, page, size),
    queryFn: () => fetchSettingsHistory(area, page, size),
    placeholderData: keepPreviousData,
  })
}
