import { useState, useCallback, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'platform-select' | 'tool-select' | 'installing' | 'done'

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

const OPTIONAL_TOOLS = [
  { id: 'tmux', name: 'tmux', desc: 'Terminal multiplexer for background sessions' },
  { id: 'ttyd', name: 'ttyd', desc: 'Web terminal — access from a browser' },
  { id: 'dufs', name: 'dufs', desc: 'File server (WebDAV)' },
  { id: 'code-server', name: 'code-server', desc: 'VS Code in browser' },
  { id: 'claude-code', name: 'Claude Code', desc: 'Anthropic AI CLI' },
  { id: 'gemini-cli', name: 'Gemini CLI', desc: 'Google AI CLI' },
  { id: 'codex-cli', name: 'Codex CLI', desc: 'OpenAI AI CLI' },
]

const TIPS = [
  'You can install multiple AI platforms and switch between them anytime.',
  'Setup is a one-time process. Future launches are instant.',
  'Once setup is complete, your AI assistant runs at full speed — just like on a computer.',
  'All processing happens locally on your device. Your data never leaves your phone.',
]

export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('platform-select')
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [tipIndex, setTipIndex] = useState(0)

  // Load available platforms
  useState(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) {
      setPlatforms(data)
    } else {
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw', icon: '🧠', desc: 'AI agent platform' },
      ])
    }
  })

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setMessage(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      setPhase('done')
    }
    setTipIndex(i => (i + 1) % TIPS.length)
  }, [])

  useNativeEvent('setup_progress', onProgress)

  function handleSelectPlatform(id: string) {
    setSelectedPlatform(id)
    setPhase('tool-select')
  }

  function toggleTool(id: string) {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function handleStartSetup() {
    // Save tool selections
    const selections: Record<string, boolean> = {}
    OPTIONAL_TOOLS.forEach(t => {
      selections[t.id] = selectedTools.has(t.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))

    // Start bootstrap setup
    setPhase('installing')
    setProgress(0)
    setMessage('Preparing setup...')
    setError('')
    bridge.call('startSetup')
  }

  // --- Stepper ---
  const currentStep = phase === 'platform-select' ? 0
    : phase === 'tool-select' ? 1
    : phase === 'installing' ? 2 : 3

  const STEPS = ['Platform', 'Tools', 'Setup']

  function renderStepper() {
    return (
      <div className="stepper">
        {STEPS.map((label, i) => (
          <Fragment key={label}>
            {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
            <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
              <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
              <span>{label}</span>
            </div>
          </Fragment>
        ))}
      </div>
    )
  }

  // --- Platform Select ---
  if (phase === 'platform-select') {
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">Choose your platform</div>

        {platforms.map(p => (
          <div
            key={p.id}
            className="card"
            style={{ maxWidth: 340, width: '100%', cursor: 'pointer' }}
            onClick={() => handleSelectPlatform(p.id)}
          >
            <div style={{ fontSize: 32, marginBottom: 8 }}>{p.icon}</div>
            <div style={{ fontSize: 18, fontWeight: 600 }}>{p.name}</div>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
              {p.desc}
            </div>
          </div>
        ))}

        <div className="setup-subtitle">More platforms available in Settings.</div>
      </div>
    )
  }

  // --- Tool Select ---
  if (phase === 'tool-select') {
    return (
      <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 48 }}>
        {renderStepper()}

        <div className="setup-title" style={{ fontSize: 22 }}>Optional Tools</div>
        <div className="setup-subtitle">
          Select tools to install alongside {selectedPlatform}. You can always add more later in Settings.
        </div>

        <div style={{ width: '100%', maxWidth: 360 }}>
          {OPTIONAL_TOOLS.map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className="card"
                style={{ cursor: 'pointer', marginBottom: 8 }}
                onClick={() => toggleTool(tool.id)}
              >
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label">{tool.name}</div>
                    <div className="card-desc">{tool.desc}</div>
                  </div>
                  <div
                    style={{
                      width: 44, height: 24, borderRadius: 12,
                      backgroundColor: isSelected ? 'var(--accent)' : 'var(--bg-tertiary)',
                      position: 'relative', flexShrink: 0,
                      transition: 'background-color 0.2s',
                    }}
                  >
                    <div style={{
                      width: 20, height: 20, borderRadius: 10,
                      backgroundColor: '#fff', position: 'absolute', top: 2,
                      left: isSelected ? 22 : 2,
                      transition: 'left 0.2s',
                      boxShadow: '0 1px 3px rgba(0,0,0,0.3)',
                    }} />
                  </div>
                </div>
              </div>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={handleStartSetup} style={{ marginTop: 8 }}>
          Start Setup
        </button>
      </div>
    )
  }

  // --- Installing ---
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">Setting up...</div>

        <div style={{ width: '100%', maxWidth: 320 }}>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${pct}%` }} />
          </div>
          <div style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
            {pct}%
          </div>
          <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
            {message}
          </div>
        </div>

        {error && (
          <div style={{ color: 'var(--error)', fontSize: 14, textAlign: 'center' }}>{error}</div>
        )}

        <div className="tip-card">💡 {TIPS[tipIndex]}</div>
      </div>
    )
  }

  // --- Done ---
  return (
    <div className="setup-container">
      {renderStepper()}
      <div className="setup-logo">✅</div>
      <div className="setup-title">You're all set!</div>
      <div className="setup-subtitle">
        The terminal will now install runtime components and your selected tools. This takes 3–10 minutes.
      </div>

      <button className="btn btn-primary" onClick={() => {
        bridge.call('showTerminal')
        onComplete()
      }}>
        Open Terminal
      </button>
    </div>
  )
}
