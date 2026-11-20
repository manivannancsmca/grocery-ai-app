import { describe, expect, it } from 'vitest'
import { fakeAuthResponse, signInAs } from '../../test/session'
import { applyAuthResponse, endSession, patchSessionUser, sessionStore } from './session'

const shownName = () => sessionStore.getSnapshot().user?.fullName

describe('patchSessionUser (a name saved on the customer profile)', () => {
  it('shows the saved name until the account reports it, through any number of renames', () => {
    signInAs(['CUSTOMER'], { fullName: 'Kavya S' })

    patchSessionUser({ fullName: 'Kavya Sriram', phone: '+919840066001' })
    expect(sessionStore.getSnapshot().user).toMatchObject({ fullName: 'Kavya Sriram', phone: '+919840066001' })

    // Renamed again before the account caught up: either older name may still come back from a refresh
    patchSessionUser({ fullName: 'Kavya R. Sriram' })
    applyAuthResponse(fakeAuthResponse('t2', { roles: ['CUSTOMER'], fullName: 'Kavya S' }))
    expect(shownName()).toBe('Kavya R. Sriram')
    applyAuthResponse(fakeAuthResponse('t3', { roles: ['CUSTOMER'], fullName: 'Kavya Sriram' }))
    expect(shownName()).toBe('Kavya R. Sriram')

    // The account has the newest name: from now on the session follows the account again
    applyAuthResponse(fakeAuthResponse('t4', { roles: ['CUSTOMER'], fullName: 'Kavya R. Sriram' }))
    applyAuthResponse(fakeAuthResponse('t5', { roles: ['CUSTOMER'], fullName: 'Kavya S' }))
    expect(shownName()).toBe('Kavya S')
  })

  it('never carries a saved name over to the next session', () => {
    signInAs(['CUSTOMER'], { fullName: 'Kavya S' })
    patchSessionUser({ fullName: 'Kavya Sriram' })

    endSession('signed-out')
    applyAuthResponse(fakeAuthResponse('t2', { roles: ['CUSTOMER'], fullName: 'Kavya S' }))

    expect(shownName()).toBe('Kavya S')
  })

  it('does nothing while signed out', () => {
    patchSessionUser({ fullName: 'Nobody' })
    expect(sessionStore.getSnapshot().user).toBeNull()
  })
})
