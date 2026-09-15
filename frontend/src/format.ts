const numberFormat = new Intl.NumberFormat("es-AR", { minimumFractionDigits: 0, maximumFractionDigits: 2 });

export function formatMoney(amount: number): string {
  return `$${numberFormat.format(amount)}`;
}
