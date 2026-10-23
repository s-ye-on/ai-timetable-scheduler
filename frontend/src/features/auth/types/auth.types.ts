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

export type LoginField = keyof LoginCredentials;
export type LoginFieldErrors = Partial<Record<LoginField, string>>;
