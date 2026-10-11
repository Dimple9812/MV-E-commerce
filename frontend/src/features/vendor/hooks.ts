import { useQuery } from '@tanstack/react-query';
import { http } from '@/lib/api';
import type { EarningsSummary, Vendor } from '@/lib/types';

export function useVendorDashboard() {
  return useQuery({
    queryKey: ['vendor', 'dashboard'],
    queryFn: () => http.get<{ vendor: Vendor; earnings: EarningsSummary }>('/api/v1/vendor/dashboard'),
  });
}
