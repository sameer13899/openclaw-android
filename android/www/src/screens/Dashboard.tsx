import { useState, useEffect, useCallback } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface BootstrapStatus {
  installed: boolean
  prefixPath?: string
}

interface PlatformInfo {
  id: string
  name: string
}

export function Dashboard() {
  const { navigate } = useRoute()
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [gatewayRunning, setGatewayRunning] = useState(false)
  const gatewayUrl = 'http://localhost:3000'
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})
  const [copied, setCopied] = useState(false)

  function refreshStatus() {
    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    // Check gateway
    const result = bridge.callJson<{ stdout: string }>('runCommand', 'pgrep -f "openclaw gateway" 2>/dev/null')
    setGatewayRunning(!!(result?.stdout?.trim()))

    // Get runtime versions
    const nodeV = bridge.callJson<{ stdout: string }>('runCommand', 'node -v 2>/dev/null')
    const gitV = bridge.callJson<{ stdout: string }>('runCommand', 'git --version 2>/dev/null')
    const ocV = bridge.callJson<{ stdout: string }>('runCommand', 'openclaw --version 2>/dev/null')
    setRuntimeInfo({
      'Node.js': nodeV?.stdout?.trim() || '—',
      'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
      'openclaw': ocV?.stdout?.trim() || '—',
    })
  }

  useEffect(() => {
    refreshStatus()
    const interval = setInterval(refreshStatus, 15000) // Poll every 15s
    return () => clearInterval(interval)
  }, [])

  const handleCommandOutput = useCallback(() => {
    // Refresh after command completes
    setTimeout(refreshStatus, 2000)
  }, [])
  useNativeEvent('command_output', handleCommandOutput)

  function handleCopy() {
    bridge.call('copyToClipboard', gatewayUrl)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  function handleCheckStatus() {
    bridge.call('showTerminal')
    bridge.call('writeToTerminal', '', 'echo "=== OpenClaw Status ==="; echo "Node.js: $(node -v)"; echo "git: $(git --version 2>/dev/null)"; echo "openclaw: $(openclaw --version 2>/dev/null)"; echo "npm: $(npm -v)"; echo "Prefix: $PREFIX"; echo "Arch: $(uname -m)"; df -h $HOME | tail -1; echo "========================"\n')
  }

  function handleUpdate() {
    bridge.call('showTerminal')
    bridge.call('writeToTerminal', '', 'npm install -g openclaw@latest --ignore-scripts && echo "Update complete. Version: $(openclaw --version)"\n')
  }

  function handleInstallTools() {
    navigate('/settings/tools')
  }

  function handleStartGateway() {
    bridge.call('showTerminal')
    // Auto-type command
    bridge.call('writeToTerminal', '', 'openclaw gateway\n')
  }

  if (!status?.installed) {
    return (
      <div className="page">
        <div className="setup-container" style={{ minHeight: 'calc(100vh - 80px)' }}>
          <div className="setup-logo">🧠</div>
          <div className="setup-title">Setup Required</div>
          <div className="setup-subtitle">
            The runtime environment hasn't been set up yet.
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      {/* Platform header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <span style={{ fontSize: 36 }}>🧠</span>
        <div>
          <div style={{ fontSize: 20, fontWeight: 700 }}>
            {platform?.name || 'OpenClaw'}
          </div>
          <div style={{ fontSize: 14, display: 'flex', alignItems: 'center', gap: 6 }}>
            <span className={`status-dot ${gatewayRunning ? 'success' : 'pending'}`} />
            {gatewayRunning ? 'Running' : 'Not running'}
          </div>
        </div>
      </div>

      {/* Gateway card */}
      {gatewayRunning ? (
        <div className="card">
          <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 8 }}>
            Gateway
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <code style={{ fontSize: 14, color: 'var(--accent)' }}>{gatewayUrl}</code>
            <button className="btn btn-small btn-secondary" onClick={handleCopy}>
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
        </div>
      ) : (
        <div className="card">
          <div style={{ textAlign: 'center', padding: '12px 0' }}>
            <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginBottom: 12 }}>
              Gateway is not running. Start it from Terminal:
            </div>
            <div className="code-block" style={{ display: 'inline-block', marginBottom: 16 }}>
              $ openclaw gateway
            </div>
            <div>
              <button className="btn btn-primary" onClick={handleStartGateway}>
                Open Terminal
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Quick actions */}
      {gatewayRunning && (
        <div className="card">
          <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12 }}>
            Quick Actions
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button
              className="btn btn-small btn-secondary"
              onClick={() => bridge.call('runCommandAsync', 'restart', 'pkill -f "openclaw gateway"; sleep 1; openclaw gateway &')}
            >
              🔄 Restart
            </button>
            <button
              className="btn btn-small btn-secondary"
              onClick={() => bridge.call('runCommandAsync', 'stop', 'pkill -f "openclaw gateway"')}
            >
              ⏹ Stop
            </button>
          </div>
        </div>
      )}

      {/* Runtime info */}
      <div className="section-title">Runtime</div>
      <div className="card">
        {Object.entries(runtimeInfo).map(([key, val]) => (
          <div className="info-row" key={key}>
            <span className="label">{key}</span>
            <span>{val}</span>
          </div>
        ))}
      </div>

      {/* Management */}
      <div className="section-title">Management</div>
      <div className="card" style={{ cursor: 'pointer' }} onClick={handleCheckStatus}>
        <div className="card-row">
          <div className="card-icon">📊</div>
          <div className="card-content">
            <div className="card-label">Status</div>
            <div className="card-desc">Check versions and environment info</div>
          </div>
          <div className="card-chevron">›</div>
        </div>
      </div>
      <div className="card" style={{ cursor: 'pointer' }} onClick={handleUpdate}>
        <div className="card-row">
          <div className="card-icon">⬆️</div>
          <div className="card-content">
            <div className="card-label">Update</div>
            <div className="card-desc">Update OpenClaw to latest version</div>
          </div>
          <div className="card-chevron">›</div>
        </div>
      </div>
      <div className="card" style={{ cursor: 'pointer' }} onClick={handleInstallTools}>
        <div className="card-row">
          <div className="card-icon">🧩</div>
          <div className="card-content">
            <div className="card-label">Install Tools</div>
            <div className="card-desc">Add or remove optional tools</div>
          </div>
          <div className="card-chevron">›</div>
        </div>
      </div>
    </div>
  )
}
