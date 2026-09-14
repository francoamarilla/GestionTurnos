import { useForm } from "react-hook-form";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "./AuthContext";

interface LoginFormValues {
  email: string;
  password: string;
}

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>();

  async function onSubmit(values: LoginFormValues) {
    try {
      await login(values.email, values.password);
      const from = (location.state as { from?: string } | null)?.from ?? "/";
      navigate(from, { replace: true });
    } catch (err) {
      const message =
        err instanceof ApiError && err.status === 401
          ? "Email o contraseña incorrectos."
          : "No se pudo iniciar sesión. Intentá de nuevo.";
      setError("root", { message });
    }
  }

  return (
    <div className="centered-page">
      <form className="card" onSubmit={handleSubmit(onSubmit)} noValidate>
        <h1>Iniciar sesión</h1>

        <label className="field">
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            {...register("email", { required: "El email es obligatorio" })}
          />
          {errors.email && <span className="error-text">{errors.email.message}</span>}
        </label>

        <label className="field">
          <span>Contraseña</span>
          <input
            type="password"
            autoComplete="current-password"
            {...register("password", { required: "La contraseña es obligatoria" })}
          />
          {errors.password && <span className="error-text">{errors.password.message}</span>}
        </label>

        {errors.root && <p className="error-text">{errors.root.message}</p>}

        <button type="submit" disabled={isSubmitting}>
          {isSubmitting ? "Ingresando..." : "Ingresar"}
        </button>
      </form>
    </div>
  );
}
