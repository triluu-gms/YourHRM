import { InputNumber, type InputNumberProps } from 'antd';

/** VND amount input: `.` thousands separator, no decimals (ADR-0034); other currencies 2 decimals. */
export function MoneyInput({ currency = 'VND', ...rest }: InputNumberProps<number> & { currency?: string }) {
  const decimals = currency === 'VND' ? 0 : 2;
  return (
    <InputNumber<number>
      style={{ width: '100%' }}
      min={0}
      precision={decimals}
      formatter={(v) => (v === undefined || v === null ? '' : String(v).replace(/\B(?=(\d{3})+(?!\d))/g, '.'))}
      parser={(v) => Number((v ?? '').replace(/\./g, '').replace(',', '.'))}
      addonAfter={currency}
      {...rest}
    />
  );
}
