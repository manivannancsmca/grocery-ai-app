import { z } from 'zod'
import { SELF_REGISTRATION_ROLES } from '../../shared/auth/roles'
import { emailField, MESSAGES, passwordField, phoneField } from '../../shared/forms/validation'

export const loginSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address.')
    .regex(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, MESSAGES.email),
  password: z.string().min(1, 'Enter your password.'),
})

/** @typedef {z.infer<typeof loginSchema>} LoginFormValues */

export const registerSchema = z
  .object({
    role: z.enum(SELF_REGISTRATION_ROLES, { error: 'Choose how you will use FreshCart.' }),
    fullName: z.string().trim().min(2, 'Enter your full name.').max(120, 'Use at most 120 characters.'),
    email: emailField(true),
    phone: phoneField(false),
    password: passwordField,
    confirmPassword: z.string().min(1, 'Confirm your password.'),
  })
  .superRefine((values, ctx) => {
    if (values.confirmPassword && values.password !== values.confirmPassword) {
      ctx.addIssue({ code: 'custom', message: 'Passwords do not match.', path: ['confirmPassword'] })
    }
  })

/** @typedef {z.infer<typeof registerSchema>} RegisterFormValues */

/** "Forgot password": just the address the link goes to. */
export const forgotPasswordSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address.')
    .regex(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, MESSAGES.email),
})

/** @typedef {z.infer<typeof forgotPasswordSchema>} ForgotPasswordFormValues */

/** A new password and its confirmation, with the registration rule (8–64 characters, a letter and a digit). */
export const resetPasswordSchema = z
  .object({
    password: passwordField,
    confirmPassword: z.string().min(1, 'Confirm your new password.'),
  })
  .superRefine((values, ctx) => {
    if (values.confirmPassword && values.password !== values.confirmPassword) {
      ctx.addIssue({ code: 'custom', message: 'Passwords do not match.', path: ['confirmPassword'] })
    }
  })

/** @typedef {z.infer<typeof resetPasswordSchema>} ResetPasswordFormValues */

/** Change password (signed in): the current one, then a new one that follows the rule and differs from it. */
export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password.'),
    password: passwordField,
    confirmPassword: z.string().min(1, 'Confirm your new password.'),
  })
  .superRefine((values, ctx) => {
    if (values.password && values.password === values.currentPassword) {
      ctx.addIssue({
        code: 'custom',
        message: 'Choose a password different from your current one.',
        path: ['password'],
      })
    }
    if (values.confirmPassword && values.password !== values.confirmPassword) {
      ctx.addIssue({ code: 'custom', message: 'Passwords do not match.', path: ['confirmPassword'] })
    }
  })

/** @typedef {z.infer<typeof changePasswordSchema>} ChangePasswordFormValues */
