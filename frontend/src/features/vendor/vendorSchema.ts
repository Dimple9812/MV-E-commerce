import { z } from 'zod';

export const vendorProfileSchema = z.object({
  businessName: z.string().trim().min(2, 'At least 2 characters').max(120),
  description: z.string().trim().max(2000).optional(),
  contactEmail: z.string().trim().email('Enter a valid e-mail address'),
  contactPhone: z.string().trim().max(30).regex(/^[0-9+()\-\s]*$/, 'Digits, spaces, + ( ) - only').optional(),
  country: z.string().trim().regex(/^[A-Za-z]{2}$/, 'Two-letter country code, e.g. US'),
});
export type VendorProfileForm = z.infer<typeof vendorProfileSchema>;
