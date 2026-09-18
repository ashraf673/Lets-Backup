# Let's Backup

**Portable • Verified • Lossless** photo & video backup for Android.

## Version 0.8 – Build 2 (Reliability)

### What works now (Build 1 + Build 2)

**Backup Engine**
- Media scanning + Album / Date / Type selection
- Choose destination folder (Internal / SD / USB)
- Create real `.lb.zip` (lossless)
- `manifest.json` + `checksums.sha256`
- Progress display

**Reliability (Build 2)**
- Detects incomplete backup on next launch
- Option to Delete incomplete state or keep it
- Keeps screen on during backup
- Partial wake lock so backup is less likely to be killed
- Progress is saved so the app knows how far it got

### How to test incomplete backup detection
1. Start a backup with many files
2. Force close the app in the middle
3. Reopen the app → you should see the "Incomplete Backup Found" dialog

### Still coming
- Build 3: Full Restore engine
- Build 4: Final UI polish
