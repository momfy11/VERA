export function SettingsPanel() {
  return (
    <section className="panel">
      <h2>Controls</h2>
      <p>Adjust memory, quiet hours, and proactive features.</p>
      <div className="toggle">
        <span>Personalization</span>
        <input type="checkbox" defaultChecked />
      </div>
      <div className="toggle">
        <span>Proactive suggestions</span>
        <input type="checkbox" defaultChecked />
      </div>
      <div className="toggle">
        <span>Quiet hours</span>
        <input type="checkbox" />
      </div>
      <div className="toggle">
        <span>Voice session active</span>
        <input type="checkbox" defaultChecked />
      </div>
    </section>
  );
}
