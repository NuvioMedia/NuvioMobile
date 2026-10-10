const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require(process.argv[2] || 'sharp');

async function main() {
    const root = path.resolve(__dirname, '../../composeApp/src/commonMain/composeResources');
    const originals = path.join(root, 'drawable');
    const files = (await fs.readdir(originals))
        .filter(name => /^rating_.*\.png$/.test(name) && name !== 'rating_tmdb_badge.png');

    // The larger TMDB integration logo must keep using the original resource.
    await fs.copyFile(path.join(originals, 'rating_tmdb.png'), path.join(originals, 'rating_tmdb_badge.png'));

    for (const [qualifier, density] of Object.entries({ldpi: 0.75, mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4})) {
        const output = path.join(root, `drawable-${qualifier}`);
        await fs.mkdir(output, {recursive: true});
        for (const file of files) {
            const height = /certified|verified_hot/.test(file) ? 24 : 16;
            const width = file === 'rating_imdb.png' ? 30 : height;
            const name = file === 'rating_tmdb.png' ? 'rating_tmdb_badge.png' : file;
            await sharp(path.join(originals, file))
                .resize(Math.round(width * density), Math.round(height * density), {
                    fit: 'inside',
                    kernel: sharp.kernel.lanczos3,
                })
                .png({compressionLevel: 9})
                .toFile(path.join(output, name));
        }
    }
    console.log(`Generated ${files.length} rating logos at six screen densities.`);
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
