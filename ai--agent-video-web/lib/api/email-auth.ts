import { http } from "./client";
import type { LoginRespVO } from "./types";
import { getGuestId } from "@/lib/guest";

export async function sendEmailCode(
  email: string,
  purpose: "REGISTER" | "RESET_PASSWORD" | "CHANGE_EMAIL"
): Promise<boolean> {
  return http.post<never, boolean>("/api/auth/email-code", { email, purpose });
}

export async function registerByEmail(
  email: string,
  code: string,
  password: string
): Promise<LoginRespVO> {
  return http.post<never, LoginRespVO>("/api/auth/register/email", {
    email,
    code,
    password,
    guestId: getGuestId(),
  });
}

export async function resetPassword(
  email: string,
  code: string,
  newPassword: string
): Promise<boolean> {
  return http.post<never, boolean>("/api/auth/password/reset", {
    email,
    code,
    newPassword,
  });
}
