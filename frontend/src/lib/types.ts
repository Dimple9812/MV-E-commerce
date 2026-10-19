// API types mirroring the backend DTOs (see contracts/api/openapi-*.json for the authoritative shapes).

export type Role = 'CUSTOMER' | 'VENDOR' | 'ADMIN';
export type Money = number;

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface User {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
  status: 'ACTIVE' | 'DEACTIVATED';
  createdAt: string;
}

export interface Address {
  id: string;
  label: string;
  recipient: string;
  line1: string;
  line2?: string;
  city: string;
  state?: string;
  postalCode: string;
  country: string;
  phone?: string;
  isDefault: boolean;
}

export interface Category {
  id: string;
  parentId?: string | null;
  name: string;
  slug: string;
  description?: string;
  sortOrder: number;
}

export interface ProductCard {
  id: string;
  name: string;
  slug: string;
  vendorId: string;
  vendorName: string;
  categoryId: string;
  minPrice: Money;
  maxPrice: Money;
  currency: string;
  ratingAvg: number;
  ratingCount: number;
  inStock: boolean;
  imageUrl?: string | null;
  publishedAt: string;
}

export interface Variant {
  id: string;
  sku: string;
  name: string;
  attributes: Record<string, string>;
  price: Money;
  compareAtPrice?: Money | null;
  active: boolean;
  availableQty: number;
}

export interface ProductImage {
  id: string;
  url: string;
  altText?: string | null;
}

export interface Product {
  id: string;
  vendor: { id: string; name: string; slug: string } | null;
  categoryId: string;
  name: string;
  slug: string;
  description: string;
  brand?: string | null;
  attributes: Record<string, string>;
  currency: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'BLOCKED';
  moderationNote?: string | null;
  minPrice?: Money | null;
  maxPrice?: Money | null;
  inStock: boolean;
  ratingAvg: number;
  ratingCount: number;
  maxPerOrder: number;
  publishedAt?: string | null;
  updatedAt: string;
  variants: Variant[];
  images: ProductImage[];
}

export interface ProductSummary {
  id: string;
  name: string;
  status: Product['status'];
  minPrice?: Money | null;
  maxPrice?: Money | null;
  inStock: boolean;
  variantCount: number;
  updatedAt: string;
  imageUrl?: string | null;
}

export interface PublicVendor {
  id: string;
  businessName: string;
  slug: string;
  description?: string;
  country: string;
  memberSince: string;
}

export type VendorStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

export interface Vendor {
  id: string;
  ownerUserId: string;
  businessName: string;
  slug: string;
  description?: string;
  contactEmail: string;
  contactPhone?: string;
  country: string;
  status: VendorStatus;
  commissionRate: number;
  statusReason?: string;
  submittedAt: string;
  decidedAt?: string;
}

export interface EarningsSummary {
  grossSales: Money;
  commission: Money;
  netEarnings: Money;
  paidOut: Money;
  balance: Money;
  salesCount: number;
  refundCount: number;
  commissionRate: number;
}

export interface EarningsEntry {
  id: string;
  orderId: string;
  suborderId: string;
  type: 'SALE' | 'REFUND';
  grossAmount: Money;
  commission: Money;
  netAmount: Money;
  currency: string;
  occurredAt: string;
}

export interface Payout {
  id: string;
  vendorId: string;
  amount: Money;
  currency: string;
  reference: string;
  status: string;
  createdAt: string;
}

export interface InventoryItem {
  variantId: string;
  productId: string;
  sku: string;
  productName: string;
  variantName: string;
  available: number;
  reserved: number;
  sold: number;
  lowStockThreshold: number;
  lowStock: boolean;
  updatedAt: string;
}

export interface StockMovement {
  id: number;
  type: string;
  deltaAvailable: number;
  deltaReserved: number;
  deltaSold: number;
  referenceType: string;
  referenceId: string;
  reason?: string;
  createdAt: string;
}

export type OrderStatus =
  | 'PENDING' | 'CONFIRMED' | 'PARTIALLY_SHIPPED' | 'SHIPPED' | 'DELIVERED'
  | 'CANCELLATION_REQUESTED' | 'CANCELLED' | 'REFUND_FAILED' | 'FAILED';

export interface OrderItem {
  id: string;
  productId: string;
  variantId: string;
  sku: string;
  productName: string;
  variantName: string;
  imageUrl?: string | null;
  quantity: number;
  unitPrice: Money;
  lineSubtotal: Money;
  discountAmount: Money;
  lineTotal: Money;
}

export interface Suborder {
  id: string;
  vendorId: string;
  vendorName: string;
  status: 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';
  itemsSubtotal: Money;
  discountAmount: Money;
  shippingAmount: Money;
  taxAmount: Money;
  total: Money;
  carrier?: string | null;
  trackingNumber?: string | null;
  shippedAt?: string | null;
  deliveredAt?: string | null;
  items: OrderItem[];
}

export interface Order {
  id: string;
  status: OrderStatus;
  checkoutState?: string | null;
  currency: string;
  itemsSubtotal: Money;
  discountTotal: Money;
  shippingTotal: Money;
  taxTotal: Money;
  grandTotal: Money;
  couponCode?: string | null;
  shippingAddress: Record<string, string>;
  cancelReason?: string | null;
  createdAt: string;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  suborders: Suborder[];
  history: { suborderId?: string | null; fromStatus?: string | null; toStatus: string; reason?: string | null; at: string }[];
}

export interface OrderSummary {
  id: string;
  status: OrderStatus;
  grandTotal: Money;
  currency: string;
  itemCount: number;
  vendorCount: number;
  createdAt: string;
}

export interface VendorSuborder {
  id: string;
  orderId: string;
  status: Suborder['status'];
  orderStatus: OrderStatus;
  shippingAddress: Record<string, string>;
  itemsSubtotal: Money;
  discountAmount: Money;
  shippingAmount: Money;
  total: Money;
  commissionRate: number;
  commissionAmount: Money;
  vendorNetAmount: Money;
  carrier?: string | null;
  trackingNumber?: string | null;
  orderedAt: string;
  shippedAt?: string | null;
  deliveredAt?: string | null;
  items: OrderItem[];
}

export interface SagaView {
  orderId: string;
  state: string;
  attempt: number;
  deadlineAt: string;
  compensationReason?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  transitions: { from?: string | null; to: string; trigger: string; eventId?: string | null; detail?: string | null; at: string }[];
}

export interface Payment {
  id: string;
  orderId: string;
  amount: Money;
  currency: string;
  status: string;
  failureCode?: string | null;
  refundedAmount: Money;
  authorizedAt?: string | null;
  capturedAt?: string | null;
  createdAt: string;
  refunds: { id: string; amount: Money; status: string; reason: string; createdAt: string; completedAt?: string | null }[];
}

export interface Review {
  id: string;
  authorName: string;
  rating: number;
  title: string;
  body: string;
  verifiedPurchase: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminReview extends Review {
  productId: string;
  customerId: string;
  status: 'PUBLISHED' | 'HIDDEN' | 'DELETED';
  moderationNote?: string | null;
}

export interface Rating {
  productId: string;
  reviewCount: number;
  averageRating: number;
  histogram: Record<string, number>;
}

export interface InboxItem {
  id: string;
  template: string;
  subject: string;
  body: string;
  link?: string | null;
  createdAt: string;
  readAt?: string | null;
}

export interface AdminOverview {
  totals: { orders: number; confirmed: number; cancelled: number; gmv: Money; averageOrderValue: Money; refundTotal: Money };
  statusDistribution: { status: string; count: number }[];
  ordersOverTime: { day: string; orders: number; gmv: Money }[];
  salesByVendor: { vendor_id: string; vendor_name: string; suborders: number; revenue: Money; commission: Money }[];
  bestSellers: { product_id: string; product_name: string; units: number; revenue: Money }[];
  payments: { status: string; count: number; amount: Money }[];
  paymentRates: { succeeded: number; failed: number; successRate: number | null /* percent, 0-100 */ };
  inventoryAlerts: { variant_id: string; sku: string; product_name: string; available: number; threshold: number; business_name?: string }[];
  operations: { deadLetters24h: number; stuckPendingOrders: number; unresolvedPayments: number; failedOrders: number };
  eventuallyConsistent: boolean;
  lastEventAt?: string | null;
  generatedAt: string;
}

export interface VendorOverview {
  totals: { orders: number; revenue: Money; commission: Money; net: Money; cancelled: number };
  salesOverTime: { day: string; orders: number; net: Money }[];
  bestSellers: { product_id: string; product_name: string; units: number; revenue: Money }[];
  inventoryAlerts: { sku: string; product_name: string; available: number; threshold: number }[];
  lastEventAt?: string | null;
}

export interface AuditEntry {
  event_id: string;
  service: string;
  actor_id?: string | null;
  actor_role?: string | null;
  action: string;
  resource_type: string;
  resource_id: string;
  outcome: string;
  details: string;
  correlation_id?: string | null;
  occurred_at: string;
}

// ---------- chat ----------

export type ChatSide = 'BUYER' | 'VENDOR';
export type ChatMessageType = 'TEXT' | 'IMAGE' | 'PRODUCT_CARD' | 'ORDER_CARD' | 'SYSTEM';

export interface ChatMessage {
  id: number;
  conversationId: string;
  senderId?: string | null;
  senderRole: ChatSide | 'SYSTEM';
  type: ChatMessageType;
  body?: string | null;
  attachmentUrl?: string | null;
  /** card snapshot (PRODUCT_CARD, ORDER_CARD), attachment info (IMAGE) or the order event (SYSTEM) */
  metadata?: Record<string, unknown> | null;
  clientMessageId?: string | null;
  createdAt: string;
  editedAt?: string | null;
  deleted: boolean;
}

export interface ChatCounterpart {
  kind: 'SHOP' | 'BUYER';
  name: string;
  vendorId?: string | null;
  vendorSlug?: string | null;
  userIds: string[];
  online: boolean;
  lastSeenAt?: string | null;
  typicalReplySeconds?: number | null;
}

export interface ChatContext {
  kind: 'PRODUCT' | 'ORDER' | 'GENERAL';
  productId?: string | null;
  productName?: string | null;
  productImageUrl?: string | null;
  price?: Money | null;
  currency?: string | null;
  orderId?: string | null;
  suborderId?: string | null;
  orderStatus?: string | null;
  itemsSummary?: string | null;
  orderTotal?: Money | null;
}

export interface Conversation {
  id: string;
  /** the viewer's side; null for an administrator */
  side: ChatSide | null;
  status: 'OPEN' | 'ARCHIVED' | 'BLOCKED';
  buyerId: string;
  buyerName: string;
  vendorId: string;
  vendorName: string;
  context: ChatContext;
  counterpart: ChatCounterpart;
  myLastReadMessageId: number;
  theirLastReadMessageId: number;
  muted: boolean;
  canWrite: boolean;
  createdAt: string;
  lastMessageAt: string;
}

export interface ConversationSummary {
  id: string;
  status: Conversation['status'];
  counterpart: ChatCounterpart;
  context: ChatContext;
  lastMessage?: { id: number; type: ChatMessageType; text: string; senderRole: string; deleted: boolean } | null;
  unread: number;
  muted: boolean;
  lastMessageAt: string;
}

export interface CursorPage<T> {
  items: T[];
  nextCursor?: string | null;
}

export interface MessagePage {
  items: ChatMessage[];
  hasMore: boolean;
  changed?: ChatMessage[];
}

export interface UnreadCounts {
  asBuyer: number;
  asVendor: number;
}

export interface QuickReply {
  id: string;
  title: string;
  body: string;
  sortOrder: number;
}

export interface ChatReport {
  id: string;
  conversationId: string;
  reporterId: string;
  reporterSide: ChatSide;
  reason: 'SPAM' | 'SCAM' | 'ABUSE' | 'OFF_PLATFORM_PAYMENT' | 'OTHER';
  details?: string | null;
  status: 'OPEN' | 'RESOLVED';
  resolution?: 'DISMISSED' | 'CONVERSATION_BLOCKED' | null;
  resolutionNote?: string | null;
  resolvedAt?: string | null;
  createdAt: string;
  vendorName: string;
  buyerName: string;
  violations: number;
}

/** Events pushed over the chat connection (STOMP user queues, or BroadcastChannel in the demo). */
export type ChatEvent =
  | { queue: 'messages'; payload: { kind: 'MESSAGE_CREATED' | 'MESSAGE_UPDATED' | 'CONVERSATION_UPDATED'; conversationId: string; message?: ChatMessage; conversationStatus?: string } }
  | { queue: 'typing'; payload: { conversationId: string; side: ChatSide; name: string; typing: boolean } }
  | { queue: 'receipts'; payload: { conversationId: string; side: ChatSide; lastReadMessageId: number } }
  | { queue: 'presence'; payload: { userId: string; online: boolean; lastSeenAt?: string | null } }
  | { queue: 'errors'; payload: { code: string; message: string; conversationId?: string | null; clientMessageId?: string | null } };
