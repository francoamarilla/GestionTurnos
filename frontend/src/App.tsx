import { BrowserRouter, Routes, Route } from "react-router-dom";

function Placeholder() {
  return (
    <div style={{ padding: 32 }}>
      <h1>Turnos</h1>
      <p>Dashboard en construcción.</p>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Placeholder />} />
      </Routes>
    </BrowserRouter>
  );
}
