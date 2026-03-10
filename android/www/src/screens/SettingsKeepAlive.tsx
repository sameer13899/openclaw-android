import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'

export function SettingsKeepAlive() {
  const { navigate } = useRoute()
  const [batteryExcluded, setBatteryExcluded] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
    if (status) setBatteryExcluded(status.isIgnoring)
  }, [])

  const ppkCommand = 'adb shell device_config set_sync_disabled_for_tests activity_manager/max_phantom_processes 2147483647'

  function handleCopyCommand() {
    bridge.call('copyToClipboard', ppkCommand)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  function handleRequestExclusion() {
    bridge.call('requestBatteryOptimizationExclusion')
    // Re-check after user returns
    setTimeout(() => {
      const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
      if (status) setBatteryExcluded(status.isIgnoring)
    }, 3000)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">Keep Alive</div>
      </div>

      <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginBottom: 20, lineHeight: 1.6 }}>
        Android may kill background processes after a while. Follow these steps to prevent it.
      </div>

      {/* 1. Battery Optimization */}
      <div className="section-title">1. Battery Optimization</div>
      <div className="card">
        <div className="card-row">
          <div className="card-content">
            <div className="card-label">Status</div>
          </div>
          {batteryExcluded ? (
            <span style={{ color: 'var(--success)', fontSize: 14 }}>✓ Excluded</span>
          ) : (
            <button className="btn btn-small btn-primary" onClick={handleRequestExclusion}>
              Request Exclusion
            </button>
          )}
        </div>
      </div>

      {/* 2. Developer Options */}
      <div className="section-title">2. Developer Options</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 12 }}>
          • Enable Developer Options<br />
          • Enable "Stay Awake"
        </div>
        <button
          className="btn btn-small btn-secondary"
          onClick={() => bridge.call('openSystemSettings', 'developer')}
        >
          Open Developer Options
        </button>
      </div>

      {/* 3. Phantom Process Killer */}
      <div className="section-title">3. Phantom Process Killer (Android 12+)</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 12 }}>
          Connect USB and enable ADB debugging, then run this command on your PC:
        </div>
        <div className="code-block">
          {ppkCommand}
          <button className="copy-btn" onClick={handleCopyCommand}>
            {copied ? 'Copied!' : 'Copy'}
          </button>
        </div>
      </div>

      {/* 4. Charge Limit */}
      <div className="section-title">4. Charge Limit (Optional)</div>
      <div className="card">
        <div style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          Set battery charge limit to 80% for always-on use. This can be configured in
          your phone's battery settings.
        </div>
      </div>
    </div>
  )
}
