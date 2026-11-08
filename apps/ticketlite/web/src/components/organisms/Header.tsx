import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth'
import { Button } from '../atoms/Button'

export function Header() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <header className="header">
      <Link to="/" className="brand">
        🎫 TicketLite
      </Link>
      <nav className="nav">
        <NavLink to="/" end>
          Concerts
        </NavLink>
        {user && <NavLink to="/orders">My orders</NavLink>}
      </nav>
      <div className="header__user">
        {user ? (
          <>
            <span>Hi, {user.displayName}</span>
            <Button variant="secondary" onClick={() => logout().then(() => navigate('/'))}>
              Log out
            </Button>
          </>
        ) : (
          <Button onClick={() => navigate('/login')}>Log in</Button>
        )}
      </div>
    </header>
  )
}
