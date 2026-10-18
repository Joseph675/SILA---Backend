package com.llanolat.sila.archivos;

import com.llanolat.sila.comprobantes.Comprobantes.Comprobante;
import com.llanolat.sila.comprobantes.Comprobantes.Detalle;
import com.llanolat.sila.comprobantes.Comprobantes.Linea;
import com.llanolat.sila.comprobantes.Comprobantes.Pago;
import com.llanolat.sila.infra.Fechas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dibuja el PDF de un comprobante interno (NO es una factura). Se genera una sola vez al registrar el movimiento y se
 * congela. Con {@link Anulacion} dibuja la version de anulacion: marca de agua «ANULADO» y el recuadro con el motivo.
 * Solo usa las fuentes estandar del PDF (Helvetica), por eso todo texto se limpia a lo que esa codificacion admite.
 */
@Component
public class GeneradorPdf {

    /** Datos de la anulacion. {@code usuario} y {@code cuando} pueden faltar si el PDF se genera despues, de respaldo. */
    public record Anulacion(String motivo, String usuario, LocalDateTime cuando) {}

    private static final PDRectangle A4 = PDRectangle.A4;
    private static final float M = 42f;                       // margen
    private static final float ANCHO = A4.getWidth() - 2 * M;
    private static final float PIE = 54f;                     // alto reservado abajo
    private static final float[] AZUL = {31 / 255f, 42 / 255f, 90 / 255f};
    private static final float[] ACENTO = {214 / 255f, 82 / 255f, 50 / 255f};
    private static final float[] NEGRO = {0.12f, 0.12f, 0.14f};
    private static final float[] GRIS_LOGO = {168 / 255f, 168 / 255f, 172 / 255f};
    private static final float[] ROJO = {176 / 255f, 32 / 255f, 32 / 255f};
    private static final float[] GRIS_TEXTO = {90 / 255f, 98 / 255f, 106 / 255f};
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Logger log = LoggerFactory.getLogger(GeneradorPdf.class);

    private final ArchivosProps.Empresa empresa;
    /** Bytes del logo (PNG o JPG), leidos una vez al arrancar; null = circulo con las iniciales. */
    private final byte[] logo;

    public GeneradorPdf(ArchivosProps props) {
        this.empresa = props.empresa();
        this.logo = leerLogo(empresa.logo());
    }

    private static byte[] leerLogo(String ruta) {
        if (!hay(ruta)) return null;
        try {
            byte[] b = Files.readAllBytes(Path.of(ruta));
            boolean png = b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            boolean jpg = b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8;
            if ((!png && !jpg) || b.length > 2_000_000) {
                log.warn("El logo {} no es PNG/JPG o pesa mas de 2 MB: se usan las iniciales.", ruta);
                return null;
            }
            return b;
        } catch (IOException e) {
            log.warn("No se pudo leer el logo {}: se usan las iniciales.", ruta);
            return null;
        }
    }

    public byte[] generar(Detalle d, Anulacion anulacion, ZonedDateTime emision) {
        try (PDDocument doc = new PDDocument()) {
            new Dibujo(doc, d, anulacion, emision).dibujar();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el PDF del comprobante.", e);
        }
    }

    // ------------------------------------------------------------------ dibujo

    private final class Dibujo {
        final PDDocument doc;
        final Detalle d;
        final Comprobante c;
        final Anulacion anulacion;
        final ZonedDateTime emision;
        final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDFont negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        final PDFont titular = negrita;
        final PDFont cursiva = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC);
        final List<PDPage> paginas = new ArrayList<>();
        PDPageContentStream cs;
        float y;

        Dibujo(PDDocument doc, Detalle d, Anulacion anulacion, ZonedDateTime emision) {
            this.doc = doc;
            this.d = d;
            this.c = d.comprobante();
            this.anulacion = anulacion;
            this.emision = emision;
        }

        void dibujar() throws IOException {
            nuevaPagina(true);
            tabla();
            totales();
            cierre();
            if (cs != null) cs.close();
            cs = null;
            decorarPaginas();
            info();
        }

        // ---- paginas

        void nuevaPagina(boolean primera) throws IOException {
            if (cs != null) cs.close();
            PDPage p = new PDPage(A4);
            doc.addPage(p);
            paginas.add(p);
            cs = new PDPageContentStream(doc, p);
            if (primera) {
                encabezado();
            } else {
                texto(titular, 14, M, A4.getHeight() - 40, limpiar(c.numero() + (anulacion != null ? "  -  ANULADO" : "") + "  (continuaci\u00f3n)"), principal());
                acento(A4.getHeight() - 50);
                y = A4.getHeight() - 74;
            }
        }

        float[] principal() { return anulacion != null ? ROJO : AZUL; }

        void encabezado() throws IOException {
            float tope = A4.getHeight() - M;
            texto(titular, 36, M, tope - 34, limpiar(tituloCorto()), principal());
            logo(A4.getWidth() - M - 32, tope - 26, 32);
            float yy = tope - 58;
            texto(negrita, 10.5f, M, yy, limpiar(empresa.nombre()), NEGRO);
            for (String dato : new String[] {hay(empresa.nit()) ? "NIT " + empresa.nit() : null, empresa.direccion(),
                                             hay(empresa.telefono()) ? "Tel. " + empresa.telefono() : null}) {
                if (!hay(dato)) continue;
                yy -= 13;
                texto(normal, 9.5f, M, yy, limpiar(dato), NEGRO);
            }
            y = Math.min(yy, tope - 98) - 30;
            datosGenerales();
            if (anulacion != null) cuadroAnulacion();
        }

        /** Logo de la empresa (PNG o JPG configurado) dentro de un circulo; sin logo, el circulo lleva las iniciales. */
        void logo(float cx, float cy, float r) throws IOException {
            if (logo != null) {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo, "logo");
                float escala = Math.min(2 * r / img.getWidth(), 2 * r / img.getHeight());
                float w = img.getWidth() * escala, h = img.getHeight() * escala;
                cs.drawImage(img, cx - w / 2, cy - h / 2, w, h);
                return;
            }
            relleno(GRIS_LOGO);
            circulo(cx, cy, r);
            cs.fill();
            String ini = iniciales(empresa.nombre());
            float tam = ini.length() > 2 ? 15 : 19;
            texto(negrita, tam, cx - ancho(negrita, tam, ini) / 2, cy - tam * 0.35f, ini, new float[] {1, 1, 1});
        }

        void circulo(float cx, float cy, float r) throws IOException {
            float k = 0.5523f * r;
            cs.moveTo(cx + r, cy);
            cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
            cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
            cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
            cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
            cs.closePath();
        }

        void cuadroAnulacion() throws IOException {
            String motivo = "Motivo: " + (hay(anulacion.motivo()) ? anulacion.motivo() : (hay(c.motivoAnulacion()) ? c.motivoAnulacion() : "no registrado"));
            String quien = anulacion.usuario() != null && anulacion.cuando() != null
                    ? "Anulado por " + anulacion.usuario() + " el " + FECHA_HORA.format(anulacion.cuando()) + " (hora de Colombia)."
                    : "Anulaci\u00f3n registrada en el sistema. Este PDF se gener\u00f3 el " + FECHA_HORA.format(emision) + " (hora de Colombia).";
            List<String> lineas = new ArrayList<>(envolver(limpiar(motivo), normal, 10, ANCHO - 24));
            lineas.add(limpiar(quien));
            lineas.add("Este es el documento de anulaci\u00f3n. El comprobante original no fue modificado.");
            float alto = 22 + lineas.size() * 14;
            relleno(new float[] {253 / 255f, 238 / 255f, 238 / 255f});
            cs.addRect(M, y - alto, ANCHO, alto);
            cs.fill();
            relleno(ROJO);
            cs.addRect(M, y - alto, 4, alto);
            cs.fill();
            float yy = y - 18;
            texto(negrita, 11, M + 14, yy, "ESTE COMPROBANTE FUE ANULADO", ROJO);
            for (String l : lineas) {
                yy -= 14;
                texto(normal, 9.5f, M + 14, yy, l, new float[] {60 / 255f, 20 / 255f, 20 / 255f});
            }
            y -= alto + 34;
        }

        /** Tres columnas, como un recibo: a quien, que y los datos del documento. */
        void datosGenerales() throws IOException {
            float col = ANCHO / 3f;
            float x1 = M, x2 = M + col, x3 = M + 2 * col + 6;
            float tope = y;

            texto(titular, 11, x1, tope, "CLIENTE".equals(c.terceroTipo()) ? "CLIENTE" : hay(c.tercero()) ? "PROVEEDOR" : "TERCERO", AZUL);
            float y1 = tope - 16;
            for (String parte : envolver(limpiar(hay(c.tercero()) ? c.tercero() : "No aplica"), normal, 9.5f, col - 14)) {
                texto(normal, 9.5f, x1, y1, parte, NEGRO);
                y1 -= 13;
            }

            texto(titular, 11, x2, tope, "DETALLE", AZUL);
            float y2 = tope - 16;
            List<String> detalle = new ArrayList<>();
            if (hay(c.descripcion())) detalle.add(c.descripcion());
            if (hay(c.metodoPago())) detalle.add("Pago: " + etiquetaMetodo(c.metodoPago()));
            if (hay(c.usuarioRegistro())) detalle.add("Registrado por " + c.usuarioRegistro());
            for (String dato : detalle) {
                for (String parte : envolver(limpiar(dato), normal, 9.5f, col - 14)) {
                    texto(normal, 9.5f, x2, y2, parte, NEGRO);
                    y2 -= 13;
                }
            }

            List<String[]> datos = new ArrayList<>();
            datos.add(new String[] {"N.\u00ba COMPROBANTE", c.numero()});
            datos.add(new String[] {"FECHA", FECHA.format(c.fecha())});
            datos.add(new String[] {"ESTADO", "ANULADO".equals(c.estado()) ? "Anulado" : "Vigente"});
            if (hay(c.referencia())) datos.add(new String[] {"REFERENCIA", c.referencia()});
            float y3 = tope;
            for (String[] f : datos) {
                texto(titular, 10, x3, y3, limpiar(f[0]), AZUL);
                derecha(normal, 9.5f, M + ANCHO, y3, limpiar(f[1]), NEGRO);
                y3 -= 17;
            }
            y = Math.min(Math.min(y1, y2), y3) - 22;
        }

        void tabla() throws IOException {
            boolean conCantidad = d.lineas().stream().anyMatch(l -> l.cantidad() != null);
            float xCant = M + 34, xConcepto = conCantidad ? M + 74 : M + 8;
            float xUnit = M + ANCHO - 120, xValor = M + ANCHO - 8;
            cabeceraTabla(conCantidad, xCant, xConcepto, xUnit, xValor);
            for (Linea l : d.lineas()) {
                float limite = (conCantidad ? xUnit - 80 : xValor - 90) - xConcepto;
                List<String> concepto = envolver(limpiar(l.concepto()), normal, 10, limite);
                float alto = Math.max(1, concepto.size()) * 13 + 11;
                if (y - alto < PIE + 70) {
                    nuevaPagina(false);
                    cabeceraTabla(conCantidad, xCant, xConcepto, xUnit, xValor);
                }
                float yy = y - 12;
                for (String parte : concepto) {
                    texto(normal, 10, xConcepto, yy, parte, NEGRO);
                    yy -= 13;
                }
                float yLinea = y - 12;
                if (conCantidad) {
                    if (l.cantidad() != null) centro(normal, 10, xCant, yLinea, cantidad(l.cantidad()), NEGRO);
                    if (l.valorUnitario() != null) derecha(normal, 10, xUnit, yLinea, dinero(l.valorUnitario()), NEGRO);
                }
                derecha(normal, 10, xValor, yLinea, dinero(l.valor()), NEGRO);
                y -= alto;
            }
            y -= 12;
        }

        void cabeceraTabla(boolean conCantidad, float xCant, float xConcepto, float xUnit, float xValor) throws IOException {
            acento(y + 14);
            float yy = y - 2;
            if (conCantidad) centro(titular, 10.5f, xCant, yy, "CANT.", AZUL);
            texto(titular, 10.5f, xConcepto, yy, limpiar("DESCRIPCI\u00d3N"), AZUL);
            if (conCantidad) derecha(titular, 10.5f, xUnit, yy, "PRECIO UNITARIO", AZUL);
            derecha(titular, 10.5f, xValor, yy, "IMPORTE", AZUL);
            acento(y - 10);
            y -= 14;
        }

        /** Linea delgada del color de acento a todo el ancho. */
        void acento(float yy) throws IOException {
            cs.setStrokingColor(ACENTO[0], ACENTO[1], ACENTO[2]);
            cs.setLineWidth(1.1f);
            cs.moveTo(M, yy);
            cs.lineTo(M + ANCHO, yy);
            cs.stroke();
        }

        void totales() throws IOException {
            espacio(86);
            float xEt = M + ANCHO - 120, xVal = M + ANCHO - 8;
            if (d.subtotal() != null) {
                derecha(normal, 10, xEt, y, "Subtotal", NEGRO);
                derecha(normal, 10, xVal, y, dinero(d.subtotal()), NEGRO);
                y -= 22;
            }
            if (d.iva() != null) {
                derecha(normal, 10, xEt, y, "IVA", NEGRO);
                derecha(normal, 10, xVal, y, dinero(d.iva()), NEGRO);
                y -= 22;
            }
            y -= 6;
            derecha(titular, 15, xEt, y, anulacion != null ? "TOTAL (ANULADO)" : "TOTAL", principal());
            derecha(titular, 15, xVal, y, dinero(c.monto()), principal());
            y -= 34;
        }

        /** Pie del documento: «Gracias» y el recuadro de condiciones y forma de pago, pegados al final de la pagina. */
        void cierre() throws IOException {
            List<String> condiciones = new ArrayList<>();
            for (Pago p : d.pagos()) {
                condiciones.add(etiquetaMetodo(p.metodoPago()) + ": " + dinero(p.monto()) + (hay(p.referencia()) ? "  (ref. " + p.referencia() + ")" : ""));
            }
            if (condiciones.isEmpty() && hay(c.metodoPago())) condiciones.add("Pago en " + etiquetaMetodo(c.metodoPago()).toLowerCase(Locale.ROOT));
            if (condiciones.isEmpty() && hay(c.descripcion()) && c.descripcion().toLowerCase(Locale.ROOT).contains("credito")) {
                condiciones.add("Venta a cr\u00e9dito: se paga seg\u00fan el plazo acordado.");
            }
            float xCaja = M + ANCHO * 0.52f, anchoCaja = M + ANCHO - xCaja - 10;
            List<String> lineas = new ArrayList<>();
            for (String linea : condiciones) lineas.addAll(envolver(limpiar(linea), normal, 9.5f, anchoCaja));
            float alto = 34 + Math.max(1, lineas.size()) * 13 + 20;
            float base = PIE + 14;
            if (y - (alto + 20) < base) {
                nuevaPagina(false);
            }
            float tope = base + alto;
            cs.setStrokingColor(AZUL[0], AZUL[1], AZUL[2]);
            cs.setLineWidth(1f);
            cs.moveTo(xCaja - 8, base);
            cs.lineTo(xCaja - 8, tope);
            cs.stroke();
            texto(titular, 11, xCaja, tope - 12, "CONDICIONES Y FORMA DE PAGO", ACENTO);
            float yy = tope - 32;
            if (lineas.isEmpty()) lineas.add("Sin pagos registrados en este documento.");
            for (String l : lineas) {
                texto(normal, 9.5f, xCaja, yy, l, NEGRO);
                yy -= 13;
            }
            texto(normal, 8, xCaja, base + 4, "Documento interno. No es factura electr\u00f3nica.", GRIS_TEXTO);
            if (anulacion == null) derecha(cursiva, 40, xCaja - 22, base + 8, "Gracias", AZUL);
        }

        /** Si no cabe lo que sigue, pasa a una pagina nueva. */
        void espacio(float necesario) throws IOException {
            if (y - necesario < PIE) nuevaPagina(false);
        }

        // ---- pie, marca de agua y metadatos (ya se sabe cuantas paginas son)

        void decorarPaginas() throws IOException {
            int total = paginas.size();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream s = new PDPageContentStream(doc, paginas.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs = s;
                    linea(M, PIE - 8, M + ANCHO, PIE - 8);
                    texto(normal, 8, M, PIE - 22, limpiar("Comprobante interno generado por SILA  -  " + c.numero()
                            + "  -  " + FECHA_HORA.format(emision) + " (hora de Colombia)"), GRIS_TEXTO);
                    derecha(normal, 8, M + ANCHO, PIE - 22, "P\u00e1gina " + (i + 1) + " de " + total, GRIS_TEXTO);
                    if (anulacion != null) marcaDeAgua();
                }
            }
            cs = null;
        }

        void marcaDeAgua() throws IOException {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.16f);
            cs.saveGraphicsState();
            cs.setGraphicsStateParameters(gs);
            relleno(ROJO);
            float tam = 120;
            float ancho = negrita.getStringWidth("ANULADO") / 1000 * tam;
            double ang = Math.toRadians(40);
            float cx = A4.getWidth() / 2, cy = A4.getHeight() / 2;
            float x0 = (float) (cx - Math.cos(ang) * ancho / 2 + Math.sin(ang) * tam * 0.35);
            float y0 = (float) (cy - Math.sin(ang) * ancho / 2 - Math.cos(ang) * tam * 0.35);
            cs.beginText();
            cs.setFont(negrita, tam);
            cs.setTextMatrix(Matrix.getRotateInstance(ang, x0, y0));
            cs.showText("ANULADO");
            cs.endText();
            cs.restoreGraphicsState();
        }

        void info() {
            PDDocumentInformation i = new PDDocumentInformation();
            i.setTitle(limpiar(titulo() + " " + c.numero()));
            i.setAuthor(limpiar(empresa.nombre()));
            i.setCreator("SILA");
            i.setProducer("SILA");
            i.setSubject(anulacion != null ? "Comprobante interno ANULADO" : "Comprobante interno");
            Calendar cal = new GregorianCalendar(TimeZone.getTimeZone(Fechas.COLOMBIA));
            cal.setTimeInMillis(emision.toInstant().toEpochMilli());
            i.setCreationDate(cal);
            doc.setDocumentInformation(i);
        }

        // ---- primitivas

        /** Palabra grande del encabezado; en la version de anulacion lleva «ANULADA/O». */
        String tituloCorto() {
            String palabra = switch (c.tipo()) {
                case "VENTA" -> "VENTA";
                case "ABONO" -> "RECIBO";
                case "PAGO_PROVEEDOR" -> "PAGO";
                case "COMPRA" -> "COMPRA";
                case "GASTO" -> "GASTO";
                default -> "COMPROBANTE";
            };
            if (anulacion == null) return palabra;
            boolean femenino = palabra.equals("VENTA") || palabra.equals("COMPRA");
            return palabra + (femenino ? " ANULADA" : " ANULADO");
        }

        String titulo() {
            return switch (c.tipo()) {
                case "VENTA" -> "Comprobante de venta";
                case "ABONO" -> "Recibo de abono";
                case "PAGO_PROVEEDOR" -> "Comprobante de pago a proveedor";
                case "COMPRA" -> "Comprobante de compra";
                case "GASTO" -> "Comprobante de gasto";
                default -> "Comprobante";
            };
        }

        void relleno(float[] rgb) throws IOException { cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]); }

        void linea(float x1, float y1, float x2, float y2) throws IOException {
            cs.setStrokingColor(0.8f, 0.82f, 0.85f);
            cs.setLineWidth(0.6f);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        void texto(PDFont f, float tam, float x, float yy, String s, float[] rgb) throws IOException {
            cs.beginText();
            cs.setFont(f, tam);
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
            cs.newLineAtOffset(x, yy);
            cs.showText(limpiar(s));
            cs.endText();
        }

        void centro(PDFont f, float tam, float xCentro, float yy, String s, float[] rgb) throws IOException {
            String t = limpiar(s);
            texto(f, tam, xCentro - ancho(f, tam, t) / 2, yy, t, rgb);
        }

        void derecha(PDFont f, float tam, float xDerecha, float yy, String s, float[] rgb) throws IOException {
            String t = limpiar(s);
            texto(f, tam, xDerecha - ancho(f, tam, t), yy, t, rgb);
        }
    }

    // ------------------------------------------------------------- utilidades

    private static float ancho(PDFont f, float tam, String s) {
        try {
            return f.getStringWidth(s) / 1000f * tam;
        } catch (IOException | IllegalArgumentException e) {
            return s.length() * tam * 0.55f;
        }
    }

    private static List<String> envolver(String texto, PDFont f, float tam, float maximo) {
        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split(" ")) {
            // una palabra mas larga que la linea se parte
            while (ancho(f, tam, palabra) > maximo && palabra.length() > 1) {
                int corte = palabra.length();
                while (corte > 1 && ancho(f, tam, palabra.substring(0, corte)) > maximo) corte--;
                if (actual.length() > 0) { lineas.add(actual.toString()); actual.setLength(0); }
                lineas.add(palabra.substring(0, corte));
                palabra = palabra.substring(corte);
            }
            String candidata = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (ancho(f, tam, candidata) <= maximo) {
                actual.setLength(0);
                actual.append(candidata);
            } else {
                lineas.add(actual.toString());
                actual.setLength(0);
                actual.append(palabra);
            }
        }
        if (actual.length() > 0 || lineas.isEmpty()) lineas.add(actual.toString());
        return lineas;
    }

    /** Una fuente por hilo: cargar las metricas de Helvetica en cada texto es caro y el objeto no se comparte entre hilos. */
    private static final ThreadLocal<PDType1Font> FUENTE_LIMPIEZA =
            ThreadLocal.withInitial(() -> new PDType1Font(Standard14Fonts.FontName.HELVETICA));

    /** Deja solo lo que Helvetica (WinAnsi) puede dibujar; lo demas se cambia por '?'. Sin saltos ni tabulaciones. */
    static String limpiar(String s) {
        if (s == null) return "";
        if (s.chars().allMatch(c -> c >= 32 && c <= 126)) return s;     // ASCII imprimible: ya es dibujable, sin tocar la fuente
        PDType1Font f = FUENTE_LIMPIEZA.get();
        StringBuilder b = new StringBuilder();
        s.replaceAll("[\\p{Cntrl}]", " ").codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                f.encode(ch);
                b.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                b.append('?');
            }
        });
        return b.toString();
    }

    private static boolean hay(String s) { return s != null && !s.isBlank(); }

    /** «Lacteos del Llano SAS» -> «LL»: primeras letras de hasta dos palabras significativas. */
    static String iniciales(String nombre) {
        StringBuilder b = new StringBuilder();
        for (String p : limpiar(nombre).trim().split("\\s+")) {
            if (p.isEmpty() || p.length() <= 2 || p.equalsIgnoreCase("SAS") || p.equalsIgnoreCase("LTDA") || p.equalsIgnoreCase("S.A.S.")) continue;
            b.append(Character.toUpperCase(p.charAt(0)));
            if (b.length() == 2) break;
        }
        String limpio = limpiar(nombre).trim();
        if (b.length() == 1 && limpio.length() > 1) b.append(Character.toUpperCase(limpio.charAt(1)));   // una sola palabra: dos letras
        if (b.length() == 0 && !limpio.isEmpty()) b.append(Character.toUpperCase(limpio.charAt(0)));
        return b.toString();
    }

    private static String etiquetaMetodo(String m) {
        if (m == null) return "";
        String t = m.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static final DecimalFormatSymbols CO = new DecimalFormatSymbols(Locale.of("es", "CO"));

    static String dinero(BigDecimal v) {
        if (v == null) return "";
        boolean entero = v.stripTrailingZeros().scale() <= 0;
        return "$ " + new DecimalFormat(entero ? "#,##0" : "#,##0.00", CO).format(v);
    }

    static String cantidad(BigDecimal v) {
        return new DecimalFormat("#,##0.###", CO).format(v);
    }
}
