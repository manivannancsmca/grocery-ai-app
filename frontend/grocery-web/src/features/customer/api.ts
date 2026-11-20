import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/** `CustomerProfile` (contract §4). customerId = userId. */
export interface CustomerProfile {
  id: string
  email: string
  fullName: string
  phone: string | null
  createdAt: string
  updatedAt: string
}

export interface UpdateCustomerProfileRequest {
  fullName: string
  phone?: string | null
}

/** `AddressRequest` — country defaults to "IN"; postalCode `^[1-9][0-9]{5}$`; lat -90..90, lng -180..180. */
export interface AddressRequest {
  label: string
  recipientName: string
  phone: string
  line1: string
  line2: string | null
  landmark: string | null
  city: string
  state: string
  postalCode: string
  country: string
  latitude: number | null
  longitude: number | null
  isDefault: boolean
}

export interface Address extends AddressRequest {
  id: string
  createdAt: string
  updatedAt: string
}

export interface AdminCustomerListParams {
  query?: string
  page?: number
  size?: number
}

/** A customer can keep at most this many addresses (`422 ADDRESS_LIMIT_REACHED`). */
export const MAX_ADDRESSES = 10

export const customerKeys = {
  all: ['customer'] as const,
  profile: () => [...customerKeys.all, 'profile'] as const,
  addresses: () => [...customerKeys.all, 'addresses'] as const,
  adminAll: ['admin', 'customers'] as const,
  adminList: (params: AdminCustomerListParams) => [...customerKeys.adminAll, params] as const,
}

export async function fetchMyProfile(): Promise<CustomerProfile> {
  return (await httpClient.get<CustomerProfile>('/customers/me')).data
}

export async function updateMyProfile(body: UpdateCustomerProfileRequest): Promise<CustomerProfile> {
  return (await httpClient.put<CustomerProfile>('/customers/me', body)).data
}

export async function fetchMyAddresses(): Promise<Address[]> {
  return (await httpClient.get<Address[]>('/customers/me/addresses')).data
}

export async function createAddress(body: AddressRequest): Promise<Address> {
  return (await httpClient.post<Address>('/customers/me/addresses', body)).data
}

export async function updateAddress(id: string, body: AddressRequest): Promise<Address> {
  return (await httpClient.put<Address>(`/customers/me/addresses/${segment(id)}`, body)).data
}

export async function deleteAddress(id: string): Promise<void> {
  await httpClient.delete(`/customers/me/addresses/${segment(id)}`)
}

export async function setDefaultAddress(id: string): Promise<Address> {
  return (await httpClient.post<Address>(`/customers/me/addresses/${segment(id)}/default`)).data
}

export async function fetchAdminCustomers(params: AdminCustomerListParams): Promise<PageResponse<CustomerProfile>> {
  return (await httpClient.get<PageResponse<CustomerProfile>>('/admin/customers', { params: cleanParams(params) })).data
}
