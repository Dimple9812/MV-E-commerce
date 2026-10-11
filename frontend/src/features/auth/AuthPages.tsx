import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState, type ReactNode } from 'react';
import { useAuth } from '@/auth/AuthProvider';
import { Field, InlineError, Input } from '@/components/ui';
import { ApiError, http } from '@/lib/api';
import { safeNext } from '@/lib/redirect';

function AuthShell({ title, children, aside }: { title: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mx-auto grid max-w-4xl gap-10 py-6 md:grid-cols-2 md:items-center">
      <div>
        <h1 className="text-4xl font-extrabold">{title}</h1>
        {aside && <div className="mt-3 text-slate">{aside}</div>}
      </div>
      <div className="panel p-6">{children}</div>
    </div>
  );
}

const loginSchema = z.object({
  email: z.string().trim().email('Enter a valid e-mail address'),
  password: z.string().min(1, 'Enter your password'),
});

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<unknown>();
  const form = useForm<z.infer<typeof loginSchema>>({ resolver: zodResolver(loginSchema) });
  const submit = form.handleSubmit(async (v) => {
    setError(undefined);
    try {
      await login(v.email, v.password);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (e) {
      setError(e);
    }
  });
  return (
    <AuthShell title="Welcome back" aside={<>New here? <Link to="/register" className="font-semibold text-ink underline">Create an account</Link>.</>}>
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <Field label="E-mail" htmlFor="email" error={form.formState.errors.email?.message}>
          <Input id="email" type="email" autoComplete="email" {...form.register('email')} />
        </Field>
        <Field label="Password" htmlFor="password" error={form.formState.errors.password?.message}>
          <Input id="password" type="password" autoComplete="current-password" {...form.register('password')} />
        </Field>
        <InlineError error={error} />
        <button className="btn-primary" disabled={form.formState.isSubmitting}>Sign in</button>
        <Link to="/forgot-password" className="text-sm text-slate hover:text-ink">Forgot your password?</Link>
      </form>
    </AuthShell>
  );
}

const passwordRule = z.string().min(10, 'At least 10 characters').max(72)
  .regex(/[A-Za-z]/, 'Include a letter').regex(/\d/, 'Include a digit');

const registerSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter your name').max(120),
  email: z.string().trim().email('Enter a valid e-mail address'),
  password: passwordRule,
});

export function RegisterPage() {
  const { register: signUp } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<unknown>();
  const form = useForm<z.infer<typeof registerSchema>>({ resolver: zodResolver(registerSchema) });
  const submit = form.handleSubmit(async (v) => {
    setError(undefined);
    try {
      await signUp(v.email, v.password, v.fullName);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (e) {
      if (e instanceof ApiError) e.fieldErrors.forEach((f) => form.setError(f.field as 'email', { message: f.message }));
      setError(e);
    }
  });
  return (
    <AuthShell title="Create your account" aside={<>Every account can shop. Want to sell? Apply from <Link to="/sell" className="font-semibold text-ink underline">Sell on MarketFlow</Link> after signing up.</>}>
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <Field label="Full name" htmlFor="fullName" error={form.formState.errors.fullName?.message}>
          <Input id="fullName" autoComplete="name" {...form.register('fullName')} />
        </Field>
        <Field label="E-mail" htmlFor="email" error={form.formState.errors.email?.message}>
          <Input id="email" type="email" autoComplete="email" {...form.register('email')} />
        </Field>
        <Field label="Password" htmlFor="password" hint="10+ characters with letters and a digit" error={form.formState.errors.password?.message}>
          <Input id="password" type="password" autoComplete="new-password" {...form.register('password')} />
        </Field>
        <InlineError error={error} />
        <button className="btn-primary" disabled={form.formState.isSubmitting}>Create account</button>
        <p className="text-sm text-slate">Already registered? <Link to="/login" className="font-semibold text-ink">Sign in</Link></p>
      </form>
    </AuthShell>
  );
}

export function ForgotPasswordPage() {
  const [sent, setSent] = useState(false);
  const form = useForm<{ email: string }>({ resolver: zodResolver(z.object({ email: z.string().trim().email('Enter a valid e-mail address') })) });
  const [error, setError] = useState<unknown>();
  const submit = form.handleSubmit(async (v) => {
    try {
      await http.post('/api/v1/auth/password-reset/request', v);
      setSent(true);
    } catch (e) {
      setError(e);
    }
  });
  return (
    <AuthShell title="Reset your password" aside="We will e-mail you a link that is valid for 30 minutes.">
      {sent ? (
        <p>If an account exists for that address, a reset link is on its way. In local development, open Mailpit at <a className="underline" href="http://localhost:8025">localhost:8025</a> to read it.</p>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
          <Field label="E-mail" htmlFor="email" error={form.formState.errors.email?.message}>
            <Input id="email" type="email" autoComplete="email" {...form.register('email')} />
          </Field>
          <InlineError error={error} />
          <button className="btn-primary" disabled={form.formState.isSubmitting}>Send reset link</button>
        </form>
      )}
    </AuthShell>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const form = useForm<{ newPassword: string }>({ resolver: zodResolver(z.object({ newPassword: passwordRule })) });
  const [error, setError] = useState<unknown>();
  const token = params.get('token') ?? '';
  const submit = form.handleSubmit(async (v) => {
    try {
      await http.post('/api/v1/auth/password-reset/confirm', { token, newPassword: v.newPassword });
      navigate('/login', { replace: true });
    } catch (e) {
      setError(e);
    }
  });
  return (
    <AuthShell title="Choose a new password" aside="All your other sessions will be signed out.">
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <Field label="New password" htmlFor="pw" hint="10+ characters with letters and a digit" error={form.formState.errors.newPassword?.message}>
          <Input id="pw" type="password" autoComplete="new-password" {...form.register('newPassword')} />
        </Field>
        <InlineError error={error} />
        <button className="btn-primary" disabled={!token || form.formState.isSubmitting}>Save new password</button>
      </form>
    </AuthShell>
  );
}
