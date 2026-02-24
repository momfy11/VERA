type Tone = "good" | "warn" | "info";

type StatusPillProps = {
  label: string;
  value: string;
  tone: Tone;
};

export function StatusPill({ label, value, tone }: StatusPillProps) {
  return (
    <div className={`status-pill ${tone}`}>
      <span>{label}</span>
      <span>{value}</span>
    </div>
  );
}
