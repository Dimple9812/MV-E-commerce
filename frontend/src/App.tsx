import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import { Link, Route, Routes } from 'react-router-dom';
import { BarChart3, Boxes, ClipboardList, FolderTree, LayoutDashboard, MessageSquare, Package, ScrollText, ShieldAlert, Store, Truck, Users, Wallet, CreditCard } from 'lucide-react';
import { RequireRole } from '@/auth/RequireRole';
import { DashboardLayout, StoreLayout, type NavItem } from '@/components/layout';
import { EmptyState, PageSpinner } from '@/components/ui';
import { HomePage } from '@/features/store/HomePage';
import { SearchPage } from '@/features/store/SearchPage';
import { ProductPage } from '@/features/store/ProductPage';
import { VendorStorePage } from '@/features/store/VendorStorePage';
import { CartPage } from '@/features/store/CartPage';
import { ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage } from '@/features/auth/AuthPages';
import { CheckoutPage } from '@/features/customer/CheckoutPage';
import { OrderDetailPage, OrdersPage } from '@/features/customer/OrderPages';
import { AccountLayout, AddressesPage, NotificationsPage, ProfilePage } from '@/features/customer/AccountPages';
import { SellPage } from '@/features/vendor/SellPage';

// Seller and admin screens are split into their own chunks: shoppers never download them.
const named = <M extends Record<string, unknown>>(load: () => Promise<M>, name: keyof M) =>
  lazy(() => load().then((m) => ({ default: m[name] as ComponentType })));
const vendorDash = () => import('@/features/vendor/VendorDashboard');
const vendorProducts = () => import('@/features/vendor/VendorProducts');
const vendorOps = () => import('@/features/vendor/VendorOps');
const adminInsights = () => import('@/features/admin/AdminInsights');
const adminManage = () => import('@/features/admin/AdminManage');
const adminOrders = () => import('@/features/admin/AdminOrders');

const VendorDashboard = named(vendorDash, 'VendorDashboard');
const VendorProducts = named(vendorProducts, 'VendorProducts');
const NewProductPage = named(vendorProducts, 'NewProductPage');
const EditProductPage = named(vendorProducts, 'EditProductPage');
const VendorInventory = named(vendorOps, 'VendorInventory');
const VendorSuborders = named(vendorOps, 'VendorSuborders');
const VendorEarnings = named(vendorOps, 'VendorEarnings');
const VendorProfile = named(vendorOps, 'VendorProfile');
const AdminOverviewPage = named(adminInsights, 'AdminOverviewPage');
const AdminOperationsPage = named(adminInsights, 'AdminOperationsPage');
const AdminAuditPage = named(adminInsights, 'AdminAuditPage');
const AdminVendorsPage = named(adminManage, 'AdminVendorsPage');
const AdminUsersPage = named(adminManage, 'AdminUsersPage');
const AdminProductsPage = named(adminManage, 'AdminProductsPage');
const AdminCategoriesPage = named(adminManage, 'AdminCategoriesPage');
const AdminReviewsPage = named(adminManage, 'AdminReviewsPage');
const AdminOrdersPage = named(adminOrders, 'AdminOrdersPage');
const AdminOrderDetailPage = named(adminOrders, 'AdminOrderDetailPage');
const AdminPaymentsPage = named(adminOrders, 'AdminPaymentsPage');

const icon = (C: typeof Store) => <C className="h-4 w-4" aria-hidden />;

const vendorNav: NavItem[] = [
  { to: '/vendor', label: 'Overview', icon: icon(LayoutDashboard), end: true },
  { to: '/vendor/products', label: 'Products', icon: icon(Package) },
  { to: '/vendor/inventory', label: 'Inventory', icon: icon(Boxes) },
  { to: '/vendor/orders', label: 'Orders', icon: icon(Truck) },
  { to: '/vendor/earnings', label: 'Earnings', icon: icon(Wallet) },
  { to: '/vendor/profile', label: 'Shop profile', icon: icon(Store) },
];

const adminNav: NavItem[] = [
  { to: '/admin', label: 'Overview', icon: icon(BarChart3), end: true },
  { to: '/admin/vendors', label: 'Sellers', icon: icon(Store) },
  { to: '/admin/users', label: 'Users', icon: icon(Users) },
  { to: '/admin/products', label: 'Products', icon: icon(Package) },
  { to: '/admin/categories', label: 'Categories', icon: icon(FolderTree) },
  { to: '/admin/orders', label: 'Orders', icon: icon(ClipboardList) },
  { to: '/admin/payments', label: 'Payments', icon: icon(CreditCard) },
  { to: '/admin/reviews', label: 'Reviews', icon: icon(MessageSquare) },
  { to: '/admin/operations', label: 'Operations', icon: icon(ShieldAlert) },
  { to: '/admin/audit', label: 'Audit log', icon: icon(ScrollText) },
];

const lazyPage = (node: ReactNode) => <Suspense fallback={<PageSpinner />}>{node}</Suspense>;

function NotFound() {
  return <EmptyState title="This page does not exist" action={<Link to="/" className="btn-primary">Back to the store</Link>}>The link may be old, or the item was removed.</EmptyState>;
}

export function App() {
  return (
    <Routes>
      <Route element={<StoreLayout />}>
        <Route index element={<HomePage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="products/:id" element={<ProductPage />} />
        <Route path="vendors/:id" element={<VendorStorePage />} />
        <Route path="cart" element={<CartPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="reset-password" element={<ResetPasswordPage />} />
        <Route path="sell" element={<SellPage />} />
        <Route path="checkout" element={<RequireRole><CheckoutPage /></RequireRole>} />
        <Route path="account" element={<RequireRole><AccountLayout /></RequireRole>}>
          <Route index element={<ProfilePage />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="orders/:id" element={<OrderDetailPage />} />
          <Route path="addresses" element={<AddressesPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>

      <Route path="vendor" element={<RequireRole role="VENDOR"><DashboardLayout title="Seller hub" items={vendorNav} /></RequireRole>}>
        <Route index element={lazyPage(<VendorDashboard />)} />
        <Route path="products" element={lazyPage(<VendorProducts />)} />
        <Route path="products/new" element={lazyPage(<NewProductPage />)} />
        <Route path="products/:productId" element={lazyPage(<EditProductPage />)} />
        <Route path="inventory" element={lazyPage(<VendorInventory />)} />
        <Route path="orders" element={lazyPage(<VendorSuborders />)} />
        <Route path="earnings" element={lazyPage(<VendorEarnings />)} />
        <Route path="profile" element={lazyPage(<VendorProfile />)} />
        <Route path="*" element={<NotFound />} />
      </Route>

      <Route path="admin" element={<RequireRole role="ADMIN"><DashboardLayout title="Administration" items={adminNav} /></RequireRole>}>
        <Route index element={lazyPage(<AdminOverviewPage />)} />
        <Route path="vendors" element={lazyPage(<AdminVendorsPage />)} />
        <Route path="users" element={lazyPage(<AdminUsersPage />)} />
        <Route path="products" element={lazyPage(<AdminProductsPage />)} />
        <Route path="categories" element={lazyPage(<AdminCategoriesPage />)} />
        <Route path="orders" element={lazyPage(<AdminOrdersPage />)} />
        <Route path="orders/:orderId" element={lazyPage(<AdminOrderDetailPage />)} />
        <Route path="payments" element={lazyPage(<AdminPaymentsPage />)} />
        <Route path="reviews" element={lazyPage(<AdminReviewsPage />)} />
        <Route path="operations" element={lazyPage(<AdminOperationsPage />)} />
        <Route path="audit" element={lazyPage(<AdminAuditPage />)} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

