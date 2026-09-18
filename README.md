# Let's Backup

**Portable • Verified • Lossless** photo & video backup for Android.

## Version 0.7 – Build 1 (Core Backup Engine)

### What works now
- Media scanning (photos + videos)
- Album selection + Select All / Clear
- Date range filter
- Media type filter (Photos / Videos / Both)
- Live size calculation
- **Choose destination folder** (Internal / SD / USB via SAF)
- **Create real `.lb.zip` archive**
- Original files copied lossless (ZIP STORED)
- `manifest.json` generated
- `checksums.sha256` (SHA-256) generated
- Progress shown in status area
- Success / error dialog

### How to test
1. Open app → Backup
2. Select albums / date / type
3. Press **START BACKUP**
4. Choose a folder (create a new folder recommended)
5. Wait for the backup to finish
6. Check the folder – you should see `LetsBackup_YYYY-MM-DD_HH-mm-ss.lb.zip`

### Still coming
- Build 2: Resume + background reliability
- Build 3: Full Restore engine
- Build 4: Final UI polish + logo + watermark + dark mode
