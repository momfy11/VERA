/**
 * Capture the browser's "beforeinstallprompt" event so the app can show
 * a custom install button. Without this, users would have to find
 * "Add to Home Screen" buried in browser menus.
 *
 * iOS Safari does NOT fire this event — those users must use the native
 * Share → Add to Home Screen flow. Detect iOS to show separate hint.
 */
import { useEffect, useState } from "react";

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
}

export function useInstallPrompt() {
  const [event, setEvent] = useState<BeforeInstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(false);

  useEffect(() => {
    const onBefore = (e: Event) => {
      e.preventDefault();
      setEvent(e as BeforeInstallPromptEvent);
    };
    const onInstalled = () => {
      setInstalled(true);
      setEvent(null);
    };
    window.addEventListener("beforeinstallprompt", onBefore);
    window.addEventListener("appinstalled", onInstalled);

    // Detect already-installed (running in standalone display mode)
    if (window.matchMedia("(display-mode: standalone)").matches) {
      setInstalled(true);
    }

    return () => {
      window.removeEventListener("beforeinstallprompt", onBefore);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, []);

  const isIOS = /iPad|iPhone|iPod/.test(
    typeof navigator !== "undefined" ? navigator.userAgent : "",
  );

  const promptInstall = async () => {
    if (!event) return false;
    await event.prompt();
    const { outcome } = await event.userChoice;
    setEvent(null);
    return outcome === "accepted";
  };

  return {
    canInstall: !!event,
    installed,
    isIOS,
    promptInstall,
  };
}
