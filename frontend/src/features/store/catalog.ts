import { useQuery } from '@tanstack/react-query';
import { http, qs } from '@/lib/api';
import type { Category, Page, Product, ProductCard, PublicVendor, Rating, Review } from '@/lib/types';

export interface SearchParams {
  q?: string;
  category?: string;
  vendorId?: string;
  minPrice?: string;
  maxPrice?: string;
  minRating?: string;
  inStock?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

export function useProducts(p: SearchParams) {
  return useQuery({
    queryKey: ['products', p],
    queryFn: ({ signal }) => http.get<Page<ProductCard>>(`/api/v1/products${qs({ ...p })}`, signal),
    placeholderData: (prev) => prev,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => http.get<Category[]>('/api/v1/categories'),
    staleTime: 10 * 60_000,
  });
}

export function useProduct(id: string | undefined) {
  return useQuery({
    queryKey: ['product', id],
    queryFn: () => http.get<Product>(`/api/v1/products/${id}`),
    enabled: !!id,
  });
}

export function useVendor(id: string | undefined) {
  return useQuery({
    queryKey: ['vendor-public', id],
    queryFn: () => http.get<PublicVendor>(`/api/v1/vendors/${id}`),
    enabled: !!id,
  });
}

export function useReviews(productId: string | undefined) {
  return useQuery({
    queryKey: ['reviews', productId],
    queryFn: () => http.get<Review[]>(`/api/v1/products/${productId}/reviews?size=20`),
    enabled: !!productId,
  });
}

export function useRating(productId: string | undefined) {
  return useQuery({
    queryKey: ['rating', productId],
    queryFn: () => http.get<Rating>(`/api/v1/products/${productId}/rating`),
    enabled: !!productId,
  });
}

export function useAvailability(variantIds: string[]) {
  return useQuery({
    queryKey: ['availability', [...variantIds].sort()],
    queryFn: () => http.get<{ variantId: string; available: number }[]>(
      `/api/v1/inventory/availability?${variantIds.map((v) => `variantIds=${v}`).join('&')}`),
    enabled: variantIds.length > 0,
    refetchInterval: 20_000,
  });
}
