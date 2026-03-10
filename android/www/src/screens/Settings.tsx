import { useRoute } from '../lib/router'

interface MenuItem {
  icon: string
  label: string
  desc: string
  route: string
  badge?: boolean
}

const MENU: MenuItem[] = [
  { icon: '📱', label: 'Platforms', desc: 'Manage installed platforms', route: '/settings/platforms' },
  { icon: '🔄', label: 'Updates', desc: 'Check for updates', route: '/settings/updates', badge: false },
  { icon: '🧰', label: 'Additional Tools', desc: 'Install extra CLI tools', route: '/settings/tools' },
  { icon: '⚡', label: 'Keep Alive', desc: 'Prevent background killing', route: '/settings/keep-alive' },
  { icon: '💾', label: 'Storage', desc: 'Manage disk usage', route: '/settings/storage' },
  { icon: 'ℹ️', label: 'About', desc: 'App info & licenses', route: '/settings/about' },
]

export function Settings() {
  const { navigate } = useRoute()

  return (
    <div className="page">
      <div className="page-title" style={{ marginBottom: 24 }}>Settings</div>
      {MENU.map(item => (
        <div key={item.route} className="card" onClick={() => navigate(item.route)}>
          <div className="card-row">
            <span className="card-icon">{item.icon}</span>
            <div className="card-content">
              <div className="card-label">{item.label}</div>
              <div className="card-desc">{item.desc}</div>
            </div>
            {item.badge && <span className="card-badge" />}
            <span className="card-chevron">›</span>
          </div>
        </div>
      ))}
    </div>
  )
}
