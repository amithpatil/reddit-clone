import { Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { CommunityDiscovery } from './pages/CommunityDiscovery';
import { CommunityPage } from './pages/CommunityPage';
import { Home } from './pages/Home';
import { Login } from './pages/Login';
import { PostDetail } from './pages/PostDetail';
import { Register } from './pages/Register';

function App() {
  const { initializing } = useAuth();

  // Blank until the silent refresh (against the httpOnly cookie) resolves, so a returning user
  // never sees a flash of "logged out" before their session is restored.
  if (initializing) {
    return null;
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Home />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/r/:communityName/comments/:postId" element={<PostDetail />} />
        <Route path="/r/:communityName" element={<CommunityPage />} />
        <Route path="/communities" element={<CommunityDiscovery />} />
      </Route>
    </Routes>
  );
}

export default App;
