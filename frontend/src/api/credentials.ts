// Field-level checks for the login/signup forms, so the message says exactly what is wrong
// with what was typed. The password rule mirrors SignupRequest on the backend
// (8-72 chars, at least one letter and one digit) - the server stays the source of truth.

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function emailProblem(email: string): string | null {
  if (!email.trim()) return 'Enter your email address.';
  if (!EMAIL_RE.test(email.trim())) return 'That email address doesn’t look right - it should be like name@example.com.';
  return null;
}

export function validateLogin(email: string, password: string): string[] {
  const problems = [emailProblem(email)];
  if (!password) problems.push('Enter your password.');
  return problems.filter((p): p is string => p !== null);
}

export function validateSignup(displayName: string, email: string, password: string): string[] {
  const problems: (string | null)[] = [];
  if (!displayName.trim()) problems.push('Enter a display name.');
  problems.push(emailProblem(email));
  if (!password) {
    problems.push('Choose a password.');
  } else {
    if (password.length < 8) problems.push(`Password is too short (${password.length} of 8 characters minimum).`);
    if (password.length > 72) problems.push('Password is too long (72 characters maximum).');
    if (!/[A-Za-z]/.test(password)) problems.push('Password needs at least one letter.');
    if (!/\d/.test(password)) problems.push('Password needs at least one number.');
  }
  return problems.filter((p): p is string => p !== null);
}
