import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSettings, fetchSettingsHistory, saveSettings, type SettingsArea, type SettingsUpdate, type SettingsView, settingsKeys } from './api'
import { optimisticView } from './fields'

/** One area's settings. Each card has its own query, so one service being down leaves the other cards working. */
export function useAreaSettings(area: SettingsArea) {
  return useQuery({ queryKey: settingsKeys.area(area), queryFn: () => fetchSettings(area) })
}

/**
 * Saves one area. Optimistic: the card shows the new values at once and goes back to the previous view when the save
 * fails. On success the server's view (new version, who changed it) replaces the guess. A 409 is not refetched
 * automatically: the admin's edits stay until they reload, so another admin's change is never overwritten unseen.
 */
export function useSaveAreaSettings(area: SettingsArea) {
  const queryClient = useQueryClient()
  const queryKey = settingsKeys.area(area)
  return useMutation({
    mutationFn: (update: SettingsUpdate) => saveSettings(area, update),
    onMutate: async (update) => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData<SettingsView>(queryKey)
      if (previous) queryClient.setQueryData<SettingsView>(queryKey, optimisticView(previous, update))
      return { previous }
    },
    onError: (_error, _update, context) => {
      if (context?.previous) queryClient.setQueryData<SettingsView>(queryKey, context.previous)
    },
    onSuccess: (view) => {
      queryClient.setQueryData<SettingsView>(queryKey, view)
      // The save added rows to the area's history.
      void queryClient.invalidateQueries({ queryKey: settingsKeys.history(area) })
    },
  })
}

/** One page of an area's change history, newest first; the page on screen stays while the next one loads. */
export function useAreaHistory(area: SettingsArea, page: number, size: number) {
  return useQuery({
    queryKey: settingsKeys.historyPage(area, page, size),
    queryFn: () => fetchSettingsHistory(area, page, size),
    placeholderData: keepPreviousData,
  })
}
