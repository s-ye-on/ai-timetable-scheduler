export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  accessTokenExpiresIn: number;
}

export interface UserProfile {
  id: number;
  name: string;
  email: string;
}

export interface LoginCredentials {
  email: string;
  password: string;
}

export interface RegistrationCredentials extends LoginCredentials {
  name: string;
}

export type LoginField = keyof LoginCredentials;
export type LoginFieldErrors = Partial<Record<LoginField, string>>;
export type RegistrationField = keyof RegistrationCredentials;
export type AuthFieldErrors = Partial<Record<RegistrationField, string>>;
