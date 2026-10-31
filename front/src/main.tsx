import { Amplify } from 'aws-amplify';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';

import { awsConfig } from './app/aws-config';
import { router } from './app/rutas';
import './styles.css';

/**
 * Amplify se configura ANTES de montar React.
 *
 * Si se hiciera despues, el primer componente que pregunte por la sesion se
 * encontraria con Amplify sin configurar y fallaria justo en la vuelta del
 * login, que es el peor momento para depurar.
 *
 * Todos los valores vienen de aws-config.ts, que genera el pipeline desde los
 * Outputs de CloudFormation: en este repositorio no hay ni un identificador
 * de AWS escrito a mano.
 */
Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId: awsConfig.userPoolId,
      userPoolClientId: awsConfig.userPoolClientId,
      loginWith: {
        oauth: {
          domain: awsConfig.hostedUiDomain,
          // Los tres scopes de OIDC (quien eres) MAS el custom scope (a que
          // tienes derecho). Sin el ultimo, el login funciona pero la API
          // responde 401: es el error mas comun de esta arquitectura, y el mas
          // confuso, porque es el mismo codigo que da un token invalido.
          scopes: ['openid', 'email', 'profile', awsConfig.scope],
          redirectSignIn: [awsConfig.redirectUrl],
          redirectSignOut: [awsConfig.redirectUrl],
          // "code" = Authorization Code Grant. Amplify le anade PKCE solo.
          responseType: 'code',
        },
      },
    },
  },
});

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
