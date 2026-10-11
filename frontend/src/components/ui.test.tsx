import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api';
import { ErrorState, Pagination, PriceTag, StatusBadge } from './ui';

describe('ui primitives', () => {
  it('ErrorState shows the server message and the correlation id for support', async () => {
    const retry = vi.fn();
    render(<ErrorState error={new ApiError({ status: 503, detail: 'Catalog is unavailable', correlationId: 'abc-123' })} onRetry={retry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Catalog is unavailable');
    expect(screen.getByText('Reference abc-123')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledOnce();
  });

  it('StatusBadge renders a readable label', () => {
    render(<StatusBadge status="RECONCILIATION_REQUIRED" />);
    expect(screen.getByText('Reconciliation required')).toBeInTheDocument();
  });

  it('PriceTag formats money', () => {
    render(<PriceTag amount={1999.9} />);
    expect(screen.getByText('$1,999.90')).toBeInTheDocument();
  });

  it('Pagination disables the edges and reports page changes', async () => {
    const onPage = vi.fn();
    render(<Pagination page={0} totalPages={3} onPage={onPage} />);
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onPage).toHaveBeenCalledWith(1);
  });

  it('Pagination hides itself for a single page', () => {
    const { container } = render(<Pagination page={0} totalPages={1} onPage={() => undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
