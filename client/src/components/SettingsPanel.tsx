const LANGUAGES = [
  { value: "sv-SE", label: "Svenska" },
  { value: "nb-NO", label: "Norsk" },
  { value: "da-DK", label: "Dansk" },
  { value: "fi-FI", label: "Suomi" },
  { value: "en-US", label: "English (US)" },
  { value: "en-GB", label: "English (UK)" },
  { value: "de-DE", label: "Deutsch" },
  { value: "fr-FR", label: "Français" },
  { value: "es-ES", label: "Español" },
  { value: "it-IT", label: "Italiano" },
  { value: "pt-BR", label: "Português (BR)" },
  { value: "nl-NL", label: "Nederlands" },
  { value: "pl-PL", label: "Polski" },
  { value: "ru-RU", label: "Русский" },
  { value: "tr-TR", label: "Türkçe" },
  { value: "ar-SA", label: "العربية" },
  { value: "ja-JP", label: "日本語" },
  { value: "zh-CN", label: "中文（简体）" },
  { value: "ko-KR", label: "한국어" },
];

type SettingsPanelProps = {
  ttsEnabled: boolean;
  onTtsEnabledChange: (v: boolean) => void;
  ttsRate: number;
  onTtsRateChange: (v: number) => void;
  lang: string;
  onLangChange: (v: string) => void;
  onClearHistory: () => void;
};

export function SettingsPanel({
  ttsEnabled,
  onTtsEnabledChange,
  ttsRate,
  onTtsRateChange,
  lang,
  onLangChange,
  onClearHistory,
}: SettingsPanelProps) {
  return (
    <section className="panel">
      <h2>Controls</h2>
      <label className="settings-select-row">
        <span>Voice language</span>
        <select value={lang} onChange={(e) => onLangChange(e.target.value)}>
          {LANGUAGES.map((l) => (
            <option key={l.value} value={l.value}>{l.label}</option>
          ))}
        </select>
      </label>
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
