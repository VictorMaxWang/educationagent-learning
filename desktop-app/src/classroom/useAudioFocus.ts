import { useEffect, useState } from 'react';
import { audioCoordinator } from './AudioCoordinator';

export function useAudioFocus() {
  const [focus, setFocus] = useState(() => audioCoordinator.snapshot());
  useEffect(() => {
    const off = audioCoordinator.subscribe(setFocus); setFocus(audioCoordinator.snapshot()); return off;
  }, []);
  return focus;
}
