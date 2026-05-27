type SettingsPanelProps = {
  ttsEnabled: boolean;
  onTtsEnabledChange: (v: boolean) => void;
  ttsRate: number;
  onTtsRateChange: (v: number) => void;
  onClearHistory: () => void;
};

export function SettingsPanel({
  ttsEnabled,
  onTtsEnabledChange,
  ttsRate,
  onTtsRateChange,
  onClearHistory,
}: SettingsPanelProps) {
  return (
    <section className="panel">
      <h2>Controls</h2>
      <div className="toggle">
        <span>Voice read-aloud</span>
        <input
          type="checkbox"
          checked={ttsEnabled}
          onChange={(e) => onTtsEnabledChange(e.target.checked)}
        />
      </div>
      {ttsEnabled && (
        <label className="settings-slider">
          <span>
            Speech speed <em>{ttsRate.toFixed(1)}×</em>
          </span>
          <input
            type="range"
            min={0.5}
            max={2}
            step={0.1}
            value={ttsRate}
            onChange={(e) => onTtsRateChange(Number(e.target.value))}
          />
        </label>
      )}
      <div className="panel-actions">
        <button type="button" className="button ghost" onClick={onClearHistory}>
          Clear conversation
        </button>
      </div>
    </section>
  );
}
