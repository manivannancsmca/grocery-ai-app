import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  type AdminComplaintParams,
  complaintKeys,
  createComplaint,
  fetchAdminComplaints,
  fetchMyComplaints,
  type MyComplaintParams,
  type NewComplaint,
  resolveComplaint,
  startComplaint,
} from './api'

/** A page of the customer's own complaints; the previous page stays on screen while the next one loads. */
export function useMyComplaints(params: MyComplaintParams) {
  return useQuery({
    queryKey: complaintKeys.mineList(params),
    queryFn: () => fetchMyComplaints(params),
    placeholderData: keepPreviousData,
  })
}

/** Raises a complaint; afterwards every complaint list is read again so the new one shows at the top. */
export function useCreateComplaint() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: NewComplaint) => createComplaint(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: complaintKeys.all }),
  })
}

/** A page of the admin queue. */
export function useAdminComplaints(params: AdminComplaintParams) {
  return useQuery({
    queryKey: complaintKeys.adminList(params),
    queryFn: () => fetchAdminComplaints(params),
    placeholderData: keepPreviousData,
  })
}

/** OPEN → IN_PROGRESS. The lists refresh afterwards — also after a conflict (someone else was quicker). */
export function useStartComplaint() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => startComplaint(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: complaintKeys.all }),
  })
}

/** → RESOLVED with the answer for the customer. The lists refresh afterwards, also after a conflict. */
export function useResolveComplaint() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, resolution }: { id: string; resolution: string }) => resolveComplaint(id, resolution),
    onSettled: () => queryClient.invalidateQueries({ queryKey: complaintKeys.all }),
  })
}
