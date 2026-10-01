import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ChatProvider } from './chat/ChatContext';
import { NotificationsProvider } from './notifications/NotificationsContext';
import { SettingsProvider } from './settings/SettingsContext';
import './index.css';
import App from './App.tsx';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <SettingsProvider>
          <NotificationsProvider>
            <ChatProvider>
              <App />
            </ChatProvider>
          </NotificationsProvider>
        </SettingsProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
