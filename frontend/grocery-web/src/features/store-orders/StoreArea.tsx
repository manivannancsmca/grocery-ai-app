import { Outlet } from 'react-router'
import { StoreOrderAlertsProvider } from './StoreOrderAlertsProvider'

/** Layout of the vendor and restaurant areas: new-order alerts on every page of the area. */
export function StoreArea() {
  return (
    <StoreOrderAlertsProvider>
      <Outlet />
    </StoreOrderAlertsProvider>
  )
}
