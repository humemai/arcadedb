#!/usr/bin/env python3
"""Extract ArcadeDB version from parent pom.xml"""
import re
import sys
from pathlib import Path

# Python-specific patch version (increment for Python-only releases)
PYTHON_PATCH = 3  # Change this to 2, 3, etc. for subsequent Python patches

def extract_version_from_pom(pom_path):
    """Extract version from Maven pom.xml and convert to PEP 440 format"""
    with open(pom_path, 'r') as f:
        content = f.read()
    
    # Find the first <version> tag after <artifactId>arcadedb-parent</artifactId>
    pattern = r'<artifactId>arcadedb-parent</artifactId>.*?<version>(.*?)</version>'
    match = re.search(pattern, content, re.DOTALL)
    
    if match:
        version = match.group(1).strip()
        # Convert Maven version to PEP 440 compatible version
        # Examples:
        #   25.10.1-SNAPSHOT -> 25.10.1.dev0
        #   25.10.1-RC1 -> 25.10.1rc1
        #   25.10.1 -> 25.10.1
        
        if '-SNAPSHOT' in version:
            version = version.replace('-SNAPSHOT', '.dev0')
        elif '-RC' in version:
            version = version.replace('-RC', 'rc')
        elif '-' in version:
            # Generic replacement for other pre-release identifiers
            version = version.replace('-', '')
        
        # Add Python patch version for Python-specific releases
        # PYTHON_PATCH = 0 → 25.9.1 (matches Java version)
        # PYTHON_PATCH = 1 → 25.9.1.1 (first Python-only patch)
        # PYTHON_PATCH = 2 → 25.9.1.2 (second Python-only patch)
        # Not applied to dev versions (e.g., 25.9.1.dev0)
        if PYTHON_PATCH > 0 and '.dev' not in version and 'rc' not in version.lower():
            version = f"{version}.{PYTHON_PATCH}"
        
        return version
    
    raise ValueError("Could not find version in pom.xml")


if __name__ == "__main__":
    # Default to parent pom.xml (two levels up from bindings/python)
    script_dir = Path(__file__).parent
    pom_path = script_dir / "../../pom.xml"
    
    if len(sys.argv) > 1:
        pom_path = Path(sys.argv[1])
    
    try:
        version = extract_version_from_pom(pom_path)
        print(version)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
