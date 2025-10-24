#!/usr/bin/env python3
"""
Download sample datasets for ArcadeDB import examples.

We introduce NULL values in some fields to test handling of missing data.

This script downloads the MovieLens Latest Small dataset, which includes:
- movies.csv: ~9,000 movies with titles and genres
    - columns: movieId,title,genres
- ratings.csv: ~100,000 user ratings
    - columns: userId,movieId,rating,timestamp
- tags.csv: User-generated tags
    - columns: userId,movieId,tag,timestamp
- links.csv: IMDb and TMDb IDs
    - columns: movieId,imdbId,tmdbId

License: Free to use for educational purposes
Source: https://grouplens.org/datasets/movielens/
"""

import os
import urllib.request
import zipfile
from pathlib import Path


def introduce_null_values(extract_dir):
    """
    Modify CSV files to introduce NULL values for testing.

    This demonstrates how the importer handles:
    - Empty strings
    - Missing values
    - Incomplete records
    """
    import csv
    import random

    print("\n🔧 Introducing NULL values in all CSV files for testing...")

    # Modify movies.csv - make some genres empty
    movies_path = extract_dir / "movies.csv"
    if movies_path.exists():
        rows = []
        with open(movies_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                # Make ~3% of genres empty (to test NULL in genre field)
                if random.random() < 0.03:
                    row["genres"] = ""
                rows.append(row)

        with open(movies_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["movieId", "title", "genres"])
            writer.writeheader()
            writer.writerows(rows)

        null_genres = sum(1 for r in rows if not r["genres"])
        print(f"   ✅ movies.csv: {null_genres} NULL genres")

    # Modify ratings.csv - make some timestamps empty
    ratings_path = extract_dir / "ratings.csv"
    if ratings_path.exists():
        rows = []
        with open(ratings_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                # Make ~2% of timestamps empty (to test NULL in numeric timestamp)
                if random.random() < 0.02:
                    row["timestamp"] = ""
                rows.append(row)

        with open(ratings_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(
                f, fieldnames=["userId", "movieId", "rating", "timestamp"]
            )
            writer.writeheader()
            writer.writerows(rows)

        null_timestamps = sum(1 for r in rows if not r["timestamp"])
        print(f"   ✅ ratings.csv: {null_timestamps} NULL timestamps")

    # Modify links.csv - make some imdbId and tmdbId values NULL
    links_path = extract_dir / "links.csv"
    if links_path.exists():
        rows = []
        with open(links_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for i, row in enumerate(reader):
                # Make ~10% of imdbId values empty
                if random.random() < 0.1:
                    row["imdbId"] = ""
                # Make ~15% of tmdbId values empty
                if random.random() < 0.15:
                    row["tmdbId"] = ""
                rows.append(row)

        # Write back
        with open(links_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["movieId", "imdbId", "tmdbId"])
            writer.writeheader()
            writer.writerows(rows)

        null_imdb = sum(1 for r in rows if not r["imdbId"])
        null_tmdb = sum(1 for r in rows if not r["tmdbId"])
        print(f"   ✅ links.csv: {null_imdb} NULL imdbId, {null_tmdb} NULL tmdbId")

    # Modify tags.csv - make some tag values empty
    tags_path = extract_dir / "tags.csv"
    if tags_path.exists():
        rows = []
        with open(tags_path, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                # Make ~5% of tags empty (to test NULL in string fields)
                if random.random() < 0.05:
                    row["tag"] = ""
                rows.append(row)

        with open(tags_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(
                f, fieldnames=["userId", "movieId", "tag", "timestamp"]
            )
            writer.writeheader()
            writer.writerows(rows)

        null_tags = sum(1 for r in rows if not r["tag"])
        print(f"   ✅ tags.csv: {null_tags} NULL tags")


def download_movielens_small():
    """Download and extract MovieLens Latest Small dataset."""

    # Create data directory
    data_dir = Path(__file__).parent / "data"
    data_dir.mkdir(exist_ok=True)

    url = "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip"
    zip_path = data_dir / "ml-latest-small.zip"
    extract_dir = data_dir / "ml-latest-small"

    # Check if already downloaded
    if extract_dir.exists():
        print(f"✅ Dataset already exists at: {extract_dir}")
        print(
            f"   - movies.csv: {(extract_dir / 'movies.csv').stat().st_size / 1024:.1f} KB"
        )
        print(
            f"   - ratings.csv: {(extract_dir / 'ratings.csv').stat().st_size / 1024:.1f} KB"
        )
        print(
            f"   - tags.csv: {(extract_dir / 'tags.csv').stat().st_size / 1024:.1f} KB"
        )
        print(
            f"   - links.csv: {(extract_dir / 'links.csv').stat().st_size / 1024:.1f} KB"
        )

        # Ask if user wants to re-introduce NULL values
        print("\n💡 To re-introduce NULL values, delete the data directory and re-run.")
        return extract_dir

    print(f"📥 Downloading MovieLens dataset from: {url}")
    print("   This may take a minute...")

    try:
        # Download with progress
        urllib.request.urlretrieve(url, zip_path)
        print(f"✅ Downloaded to: {zip_path}")

        # Extract
        print("📦 Extracting...")
        with zipfile.ZipFile(zip_path, "r") as zip_ref:
            zip_ref.extractall(data_dir)

        print(f"✅ Extracted to: {extract_dir}")

        # Introduce NULL values for testing
        introduce_null_values(extract_dir)

        # Show file sizes
        print(f"\n📊 Dataset contents:")
        for csv_file in extract_dir.glob("*.csv"):
            size_kb = csv_file.stat().st_size / 1024
            print(f"   - {csv_file.name}: {size_kb:.1f} KB")

        # Clean up zip file
        zip_path.unlink()
        print("\n🧹 Cleaned up zip file")

        return extract_dir

    except Exception as e:
        print(f"❌ Error downloading dataset: {e}")
        print(f"   You can manually download from: {url}")
        raise


def main():
    """Main function."""
    print("=" * 70)
    print("📥 MovieLens Dataset Download")
    print("=" * 70)
    print()

    extract_dir = download_movielens_small()

    print()
    print("=" * 70)
    print("✅ Dataset ready!")
    print("=" * 70)
    print()
    print("💡 Use this dataset in examples:")
    print(f"   movies_csv = '{extract_dir / 'movies.csv'}'")
    print(f"   ratings_csv = '{extract_dir / 'ratings.csv'}'")
    print()
    print("📚 Dataset info:")
    print("   - ~9,000 movies")
    print("   - ~100,000 ratings")
    print("   - ~600 users")
    print("   - Licensed for educational use")
    print()


if __name__ == "__main__":
    main()
