export function Placeholder({ title, note }: { title: string; note?: string }) {
  return (
    <div>
      <h1>{title}</h1>
      <p>{note ?? "Pantalla en construcción."}</p>
    </div>
  );
}
