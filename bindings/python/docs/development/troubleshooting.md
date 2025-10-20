# Troubleshooting

!!! info "Under Construction"
    This page is being developed. Check back soon!

## Common Issues

### Java Not Found

```bash
# Install JRE
sudo apt-get install default-jre-headless  # Ubuntu/Debian
brew install openjdk                        # macOS
```

### Import Errors

```bash
pip uninstall -y arcadedb-embedded arcadedb-embedded-headless arcadedb-embedded-minimal
pip install arcadedb-embedded-headless
```

## Coming Soon

- Detailed troubleshooting guide
- Common errors and solutions
- Performance tuning
- Debug logging
