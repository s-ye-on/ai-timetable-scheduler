## flow

1. 사용자가 내 서비스에 **자체 로그인(JWT)** 한다. 
2. 로그인된 사용자가 "Google Calendar 연결" 버튼을 누른다
3. 서버가 Google Oauth 2.0 authorization code flow 로 Google 동의 화면으로 리다이렉트한다. 
4. 사용자가 동의하면 Google이 서버의 redirect URI로 authorization code를 돌려준다
5. 서버가 그 code를 Google 토큰 엔드 포인트에서 교환해서 access token + refresh token을 받는다 
6. 서버가 이 토큰을 사용자 계정과 매핑해서 저장
7. 이후 일정 조회/생성 요청이 오면 서버가 저장된 토큰으로 Calendar API를 호출
8. access token이 만료되면 서버가 refresh token으로 재발급받아 계속 사용. 이건 Google의 웹 서버용 OAuth 2.0흐름과
    Spring Security OAuth2 Client가 지원하는 전형적인 패턴.
  

내 서비스 인증은 JWT로 처리, 개인 캘린더 접근은 Google OAuth2 authorization code flow로 연결
사용자의 Google 토큰은 서버 DB에 안전하게 저장, 일정 조회 시 서버가 대신 Google API를 호출
