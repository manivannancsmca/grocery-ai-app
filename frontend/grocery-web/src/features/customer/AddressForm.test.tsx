import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/problemDetail'
import { AddressForm } from './AddressForm'
import { emptyAddressValues } from './addressSchema'

function renderForm(onSubmit = vi.fn().mockResolvedValue(undefined)) {
  render(
    <AddressForm
      defaultValues={emptyAddressValues({ name: 'Asha Rao', phone: '+919876543210' })}
      submitLabel="Add address"
      onSubmit={onSubmit}
      onCancel={vi.fn()}
    />,
  )
  return onSubmit
}

const field = (label: RegExp) => screen.getByLabelText(label)

async function fillValidAddress(user: ReturnType<typeof userEvent.setup>) {
  await user.type(field(/^label/i), 'Home')
  await user.type(field(/house \/ flat/i), '12 MG Road')
  await user.type(field(/^city/i), 'Bengaluru')
  await user.type(field(/^state/i), 'Karnataka')
  await user.type(field(/pin code/i), '560001')
  await user.type(field(/^latitude/i), '12.9716')
  await user.type(field(/^longitude/i), '77.5946')
}

describe('AddressForm', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'geolocation')
  })

  it('requires the address fields', async () => {
    const user = userEvent.setup()
    const onSubmit = renderForm()

    await user.click(screen.getByRole('button', { name: 'Add address' }))

    expect(await screen.findByText('Give the address a label, e.g. Home.')).toBeInTheDocument()
    expect(screen.getByText('Enter the house / flat and street.')).toBeInTheDocument()
    expect(field(/pin code/i)).toHaveAttribute('aria-invalid', 'true')
    expect(field(/^latitude/i)).toHaveAttribute('aria-invalid', 'true')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('checks the PIN code, phone and coordinate formats', async () => {
    const user = userEvent.setup()
    const onSubmit = renderForm()

    await fillValidAddress(user)
    await user.clear(field(/pin code/i))
    await user.type(field(/pin code/i), '012345')
    await user.clear(field(/^latitude/i))
    await user.type(field(/^latitude/i), '95')
    await user.clear(field(/^phone/i))
    await user.type(field(/^phone/i), '12345')
    await user.click(screen.getByRole('button', { name: 'Add address' }))

    expect(await screen.findByText('Enter a valid 6-digit PIN code.')).toBeInTheDocument()
    expect(screen.getByText('Latitude must be a number between -90 and 90.')).toBeInTheDocument()
    expect(screen.getByText('Enter 10–15 digits, optionally starting with +.')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits a typed AddressRequest', async () => {
    const user = userEvent.setup()
    const onSubmit = renderForm()

    await fillValidAddress(user)
    await user.click(screen.getByRole('checkbox', { name: /default delivery address/i }))
    await user.click(screen.getByRole('button', { name: 'Add address' }))

    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit).toHaveBeenCalledWith({
      label: 'Home',
      recipientName: 'Asha Rao',
      phone: '+919876543210',
      line1: '12 MG Road',
      line2: null,
      landmark: null,
      city: 'Bengaluru',
      state: 'Karnataka',
      postalCode: '560001',
      country: 'IN',
      latitude: 12.9716,
      longitude: 77.5946,
      isDefault: true,
    })
  })

  it('fills latitude and longitude from the browser location', async () => {
    const user = userEvent.setup()
    // jsdom has no geolocation; provide a fake that answers immediately.
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) =>
          success({ coords: { latitude: 12.97159912, longitude: 77.59456 } } as GeolocationPosition),
      },
    })
    renderForm()

    await user.click(screen.getByRole('button', { name: 'Use my current location' }))

    expect(field(/^latitude/i)).toHaveValue('12.971599')
    expect(field(/^longitude/i)).toHaveValue('77.59456')
  })

  it('explains the 10-address limit returned by the server', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn().mockRejectedValue(
      new ApiError('Address limit reached', {
        kind: 'http',
        status: 422,
        problem: { status: 422, errorCode: 'ADDRESS_LIMIT_REACHED', detail: 'Address limit reached' },
        correlationId: null,
      }),
    )
    renderForm(onSubmit)

    await fillValidAddress(user)
    await user.click(screen.getByRole('button', { name: 'Add address' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/already have 10 saved addresses/i)
  })
})
