import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

export function SettingsPlatforms() {
  const { navigate } = useRoute()
  const [available, setAvailable] = useState<Platform[]>([])
  const [active, setActive] = useState('')
  const [installing, setInstalling] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)

  useEffect(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) setAvailable(data)

    const ap = bridge.callJson<{ id: string }>('getActivePlatform')
    if (ap) setActive(ap.id)
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { target?: string; progress?: number }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.progress !== undefined && d.progress >= 1) {
      if (d.target) setActive(d.target)
      setInstalling(null)
    }
  }, [])
  useNativeEvent('install_progress', onProgress)


  function handleInstall(id: string) {
    setInstalling(id)
    setProgress(0)
    bridge.call('installPlatform', id)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">Platforms</div>
      </div>

      {installing && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 14, marginBottom: 8 }}>Installing {installing}...</div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        </div>
      )}

      {available.map(p => {
        const isActive = p.id === active
        return (
          <div key={p.id} className="card">
            <div className="card-row">
              <span className="card-icon">{p.icon}</span>
              <div className="card-content">
                <div className="card-label">
                  {p.name}
                  {isActive && (
                    <span style={{ color: 'var(--success)', fontSize: 12, marginLeft: 8 }}>
                      Active
                    </span>
                  )}
                </div>
                <div className="card-desc">{p.desc}</div>
              </div>
              {!isActive && (
                <button
                  className="btn btn-small btn-primary"
                  onClick={() => handleInstall(p.id)}
                  disabled={installing !== null}
                >
                  Install & Switch
                </button>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
