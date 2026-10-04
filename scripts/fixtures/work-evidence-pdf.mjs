// Uncompressed, genuine PDF attachment so ClamAV scans the canonical test file.
export function embeddedTestPdf(bytes) {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles 7 0 R >> >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents 4 0 R >>',
    '<< /Length 0 >>\nstream\n\nendstream',
    '<< /Type /Filespec /F (eicar.txt) /UF (eicar.txt) /EF << /F 6 0 R >> >>',
    Buffer.concat([Buffer.from(`<< /Type /EmbeddedFile /Length ${bytes.length} >>\nstream\n`), bytes, Buffer.from('\nendstream')]),
    '<< /Names [(eicar.txt) 5 0 R] >>'
  ];
  const parts = [Buffer.from('%PDF-1.4\n')], offsets = [0];
  for (let index = 0; index < objects.length; index++) {
    offsets.push(parts.reduce((total, part) => total + part.length, 0));
    parts.push(Buffer.from(`${index + 1} 0 obj\n`), Buffer.from(objects[index]), Buffer.from('\nendobj\n'));
  }
  const xref = parts.reduce((total, part) => total + part.length, 0);
  parts.push(Buffer.from(`xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`));
  return Buffer.concat(parts);
}
