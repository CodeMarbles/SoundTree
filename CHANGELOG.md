# Change Log

## 0.10.25
- Fixed startup behavior where sometimes the app would restore to the Listen tab or Library/Details tab with nothing loaded.
- Added configuration option for the default startup tab (Record or Library with subtab options).
## 0.10.24
- Moved orphan recording scan out of SplashActivity and into MainViewModel  so it runs in the background after launch instead of blocking it
## 0.10.23
- Fixed: Backup progress bar for recordings wasn't updating for individual file copies of recordings