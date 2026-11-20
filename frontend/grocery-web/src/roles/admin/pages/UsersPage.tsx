import { useState } from 'react'
import { useSearchParams } from 'react-router'
import type { AdminUserStatus, AdminUserView } from '../../../features/users/api'
import { CreateAdminDialog } from '../../../features/users/CreateAdminDialog'
import { useReactivateUser, useSuspendUser, useUsers } from '../../../features/users/hooks'
import { ApiError, errorMessage, hasErrorCode } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { isAdminRole, isUserRole, roleLabel, USER_ROLES } from '../../../shared/auth/roles'
import { useAuth } from '../../../shared/auth/useAuth'
import { ReasonDialog } from '../../../shared/components/ReasonDialog'
import { useToast } from '../../../shared/components/toast/useToast'
import {
  Avatar,
  Badge,
  type BadgeTone,
  Button,
  type Column,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Skeleton,
  Spinner,
  type StatAccent,
  StatCard,
  TextField,
  type TableSort,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { Icon, type IconName } from '../../../shared/icons'
import { formatDateTime } from '../../../shared/utils/format'

const USER_STATUSES: AdminUserStatus[] = ['ACTIVE', 'SUSPENDED']

function isUserStatus(value: string | null): value is AdminUserStatus {
  return value === 'ACTIVE' || value === 'SUSPENDED'
}

function pageFromParam(value: string | null): number {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed - 1 : 0
}

function statusLabel(status: string): string {
  return status === 'ACTIVE' ? 'Active' : status === 'SUSPENDED' ? 'Suspended' : status
}

/** A colour per kind of account, so a mixed list reads at a glance (all admin roles share violet). */
function roleTone(role: string): BadgeTone {
  if (isAdminRole(role)) return 'violet'
  if (role === 'VENDOR') return 'amber'
  if (role === 'RESTAURANT_OWNER') return 'green'
  if (role === 'DELIVERY_PARTNER') return 'blue'
  return 'gray'
}

interface UserCountTileProps {
  label: string
  status?: AdminUserStatus
  icon: IconName
  accent: StatAccent
  className?: string
}

/** One headline count: a one-row page of the user list, so only the total travels (refreshed after suspend / reactivate). */
function UserCountTile({ label, status, icon, accent, className }: UserCountTileProps) {
  const count = useUsers({ status, page: 0, size: 1 })
  const value =
    count.data !== undefined ? (
      count.data.totalElements.toLocaleString('en-IN')
    ) : count.isPending ? (
      <Skeleton className="my-1 h-6 w-10" />
    ) : (
      '—'
    )
  return <StatCard label={label} value={value} icon={icon} accent={accent} className={className} />
}

/** All accounts (any role): search, filter, suspend / reactivate, and create admins (SUPER_ADMIN). */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['user', 'status', 'created'] as const

export function UsersPage() {
  const [sort, setSort] = useState<TableSort | null>(null)
  const { user: me, can } = useAuth()
  const toast = useToast()
  const [searchParams, setSearchParams] = useSearchParams()
  const [createOpen, setCreateOpen] = useState(false)
  const [suspendTarget, setSuspendTarget] = useState<AdminUserView | null>(null)
  const [reactivateTarget, setReactivateTarget] = useState<AdminUserView | null>(null)
  const [reactivateError, setReactivateError] = useState<string | null>(null)
  const suspendUser = useSuspendUser()
  const reactivateUser = useReactivateUser()

  const queryText = searchParams.get('q') ?? ''
  const roleParam = searchParams.get('role')
  const role = isUserRole(roleParam) ? roleParam : undefined
  const statusParam = searchParams.get('status')
  const status = isUserStatus(statusParam) ? statusParam : undefined
  const page = pageFromParam(searchParams.get('page'))
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)

  const users = useUsers({ query: debouncedQuery || undefined, role, status, page, size: DEFAULT_PAGE_SIZE })
  const canManage = can('users.manage')

  function updateParams(changes: Record<string, string | null>) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === null || value === '') next.delete(key)
          else next.set(key, value)
        }
        return next
      },
      { replace: true },
    )
  }

  // How many filters are on (for the filter card), and how to switch them all off again.
  const activeFilters = (queryText.trim() ? 1 : 0) + (role ? 1 : 0) + (status ? 1 : 0)
  const clearFilters = () => updateParams({ q: null, role: null, status: null, page: null })

  const columns: Column<AdminUserView>[] = [
    {
      key: 'user',
      header: 'User',
      sortValue: (user) => user.fullName,
      cell: (user) => (
        <div className="flex min-w-0 items-center gap-3">
          <Avatar name={user.fullName} size="sm" />
          <div className="min-w-0">
            <p className="font-medium text-slate-900">
              {user.fullName}
              {user.id === me?.id && <span className="ml-2 text-xs font-normal text-slate-400">(you)</span>}
            </p>
            <p className="truncate text-xs text-slate-500">{user.email}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'roles',
      header: 'Roles',
      sortValue: (user) => user.roles.map(roleLabel).join(', '),
      cell: (user) => (
        <span className="flex flex-wrap gap-1">
          {user.roles.map((r) => (
            <Badge key={r} tone={roleTone(r)}>
              {roleLabel(r)}
            </Badge>
          ))}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (user) => statusLabel(user.status),
      cell: (user) => (
        <Badge tone={user.status === 'ACTIVE' ? 'green' : 'red'} dot>
          {statusLabel(user.status)}
        </Badge>
      ),
    },
    // ISO timestamps sort correctly as text; users who never signed in (null) go last.
    { key: 'created', header: 'Joined', hideOnMobile: true, sortValue: (user) => user.createdAt, cell: (user) => formatDateTime(user.createdAt) },
    {
      key: 'lastLogin',
      header: 'Last sign-in',
      hideOnMobile: true,
      sortValue: (user) => user.lastLoginAt,
      cell: (user) => formatDateTime(user.lastLoginAt),
    },
  ]

  // A row has at most one action (suspend or reactivate), so it stays a visible button rather than a "⋯" menu.
  if (canManage) {
    columns.push({
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (user) =>
        user.id === me?.id ? null : user.status === 'SUSPENDED' ? (
          <Button variant="secondary" size="sm" onClick={() => setReactivateTarget(user)} aria-label={`Reactivate ${user.fullName}`}>
            <Icon name="refresh" className="size-4" />
            Reactivate
          </Button>
        ) : (
          <Button
            variant="ghost"
            size="sm"
            className="text-red-600 hover:bg-red-50 hover:text-red-700"
            onClick={() => setSuspendTarget(user)}
            aria-label={`Suspend ${user.fullName}`}
          >
            <Icon name="ban" className="size-4" />
            Suspend
          </Button>
        ),
    })
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon="user"
        title="Users"
        description="Every account on the platform. Suspending a user signs them out on their next request."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ExportButton
              path="/admin/users/export"
              label="users"
              params={{ query: debouncedQuery || undefined, role, status }}
              total={users.data?.totalElements}
              sort={sort}
              sortable={EXPORT_SORTS}
            />
            {can('admins.create') && (
              <Button onClick={() => setCreateOpen(true)}>
                <Icon name="plus" className="size-4" />
                Create admin user
              </Button>
            )}
          </div>
        }
      />

      <section aria-label="Accounts at a glance">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <UserCountTile label="All accounts" icon="users" accent="sky" className="col-span-2 sm:col-span-1" />
          <UserCountTile label="Active" status="ACTIVE" icon="checkCircle" accent="emerald" />
          <UserCountTile label="Suspended" status="SUSPENDED" icon="ban" accent="rose" />
        </dl>
      </section>

      <FilterBar activeCount={activeFilters} onClear={clearFilters}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <TextField
            label="Search users"
            type="search"
            value={queryText}
            placeholder="Search by name, email or phone"
            containerClassName="sm:col-span-2"
            onChange={(event) => updateParams({ q: event.target.value, page: null })}
          />
          <SelectField label="Filter by role" value={role ?? ''} onChange={(event) => updateParams({ role: event.target.value, page: null })}>
            <option value="">All roles</option>
            {USER_ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel(r)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Filter by status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
            <option value="">All statuses</option>
            {USER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {statusLabel(s)}
              </option>
            ))}
          </SelectField>
        </div>
      </FilterBar>

      {users.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading users" />
        </div>
      ) : users.isError ? (
        <ErrorState
          title="Could not load users"
          message={users.error.message}
          correlationId={users.error instanceof ApiError ? users.error.correlationId : null}
          onRetry={() => void users.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Users"
            columns={columns}
            rows={users.data.content}
            getRowKey={(user) => user.id}
            sort={sort}
            onSortChange={setSort}
            fetching={users.isFetching && users.isPlaceholderData}
            empty={
              <EmptyState
                title="No users found"
                description="No account matches this search and these filters. Try a different search, or clear the filters."
                action={
                  activeFilters > 0 ? (
                    <Button variant="secondary" size="sm" onClick={clearFilters}>
                      <Icon name="close" className="size-4" />
                      Clear filters
                    </Button>
                  ) : undefined
                }
              />
            }
          />
          <Pagination
            page={users.data.page}
            size={users.data.size}
            totalElements={users.data.totalElements}
            totalPages={users.data.totalPages}
            disabled={users.isFetching}
            onPageChange={(next) => updateParams({ page: next > 0 ? String(next + 1) : null })}
          />
        </div>
      )}

      <CreateAdminDialog open={createOpen} onClose={() => setCreateOpen(false)} />

      <ReasonDialog
        open={suspendTarget !== null}
        title={`Suspend ${suspendTarget?.fullName ?? 'user'}`}
        description="They are signed out immediately and cannot sign in until reactivated."
        confirmLabel="Suspend user"
        onClose={() => setSuspendTarget(null)}
        onSubmit={async (reason) => {
          if (!suspendTarget) return
          try {
            await suspendUser.mutateAsync({ id: suspendTarget.id, reason })
          } catch (error) {
            if (hasErrorCode(error, 'CANNOT_SUSPEND_SELF')) throw new Error('You cannot suspend your own account.', { cause: error })
            throw error
          }
          toast.success(`${suspendTarget.fullName} suspended.`)
          setSuspendTarget(null)
        }}
      />

      <ConfirmDialog
        open={reactivateTarget !== null}
        title={`Reactivate ${reactivateTarget?.fullName ?? 'user'}?`}
        confirmLabel="Reactivate"
        pending={reactivateUser.isPending}
        error={reactivateError}
        onClose={() => {
          setReactivateTarget(null)
          setReactivateError(null)
        }}
        onConfirm={() => {
          if (!reactivateTarget) return
          setReactivateError(null)
          reactivateUser.mutate(reactivateTarget.id, {
            onSuccess: () => {
              toast.success(`${reactivateTarget.fullName} reactivated.`)
              setReactivateTarget(null)
            },
            onError: (error) => setReactivateError(errorMessage(error)),
          })
        }}
      >
        <p>They can sign in again straight away.</p>
      </ConfirmDialog>
    </div>
  )
}
