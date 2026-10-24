package io.kilnmark.kiln.plugins.files;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRelation;

import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;
import io.kilnmark.kiln.plugins.documents.Names;
import io.kilnmark.kiln.plugins.documents.OfficeParts;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/**
 * Excel sheets as tables. A sheet is a table when its first non-empty row is a header: at least
 * two names, all text, side by side with no gaps; at least one row below it; and rows no wider than
 * the header. Other sheets (notes, charts, forms) are recorded with the reason they were not read.
 *
 * <p>.xlsx is read with POI's streaming (event) API: the package parts through
 * {@link XSSFReader}, each sheet's XML pulled row by row with StAX, so memory holds one row, plus
 * the workbook's shared strings, read through a stream that stops at
 * {@code kilnmark.documents.max-part-mb} (and the whole file is checked by {@link OfficeParts}
 * first: what a part really expands to, not what the archive declares). Rows stop at Excel's
 * {@value #MAX_COLUMNS} columns. Legacy .xls is
 * read whole with POI's HSSF, so it is limited to {@code kilnmark.documents.max-legacy-file-mb}.
 *
 * <p>A column is typed when every sampled cell in it is: boolean, int64 (whole numbers), float64,
 * date (dates at midnight) or timestamp; otherwise text, numbers and dates written as text.
 */
final class Sheets {

    /** Excel's own most columns in a row (A to XFD); a row with more cells is refused. */
    static final int MAX_COLUMNS = 16_384;

    /** A cell that holds an error (#DIV/0!, #N/A): read as null. */
    static final Object ERROR = new Object() {
        @Override
        public String toString() {
            return "#ERROR";
        }
    };

    private Sheets() {
    }

    /**
     * What a sheet holds.
     *
     * @param index     0-based position in the workbook
     * @param columns   the table's columns; empty when {@code problem} is set
     * @param headerRow 1-based row number of the header
     * @param firstColumn 0-based column of the header's first name
     * @param problem   why the sheet is not read as a table, or null
     */
    record Layout(String name, int index, List<FileColumn> columns, int headerRow, int firstColumn, String problem) {
    }

    /** One row: its 1-based number and its cells by 0-based column (null when empty). */
    record SheetRow(int number, List<Object> cells) {
        Object cell(int column) {
            return column < cells.size() ? cells.get(column) : null;
        }

        boolean blank() {
            for (Object c : cells) {
                if (c != null && !(c instanceof String s && s.isBlank())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A sheet's rows, read one at a time. */
    interface SheetRows extends Closeable {
        /** The next row with any cell, or null at the end. */
        SheetRow next() throws IOException;
    }

    /** A workbook open for reading. */
    interface Workbook extends Closeable {
        List<String> sheetNames();

        SheetRows rows(String sheet) throws IOException;
    }

    static Workbook open(Path file, String name) throws IOException {
        String ext = DocumentTypes.extension(name);
        if (ext.equals("xls")) {
            return new Hssf(file);
        }
        try {
            OfficeParts.check(file, DocumentRuntime.limits().maxPartBytes(), true);
        } catch (DocumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        return new Xssf(file);
    }

    /**
     * Sheet names made safe to store and show ({@link Names}), and unique: two names that clean to
     * the same text get " (2)", " (3)", ...
     */
    static List<String> safeNames(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String base = Names.clean(raw.get(i), "Sheet" + (i + 1));
            String n = base;
            for (int k = 2; out.contains(n); k++) {
                n = base + " (" + k + ")";
            }
            out.add(n);
        }
        return out;
    }

    /** Inspects every sheet, sampling {@code sampleRows} rows below each header. */
    static List<Layout> inspect(Workbook wb, int sampleRows) throws IOException {
        return inspect(wb, sampleRows, new java.util.concurrent.atomic.AtomicBoolean());
    }

    /** As {@link #inspect(Workbook, int)}, stopping between sheets once {@code cancelled} turns true. */
    static List<Layout> inspect(Workbook wb, int sampleRows, java.util.concurrent.atomic.AtomicBoolean cancelled)
        throws IOException {
        List<Layout> out = new ArrayList<>();
        List<String> names = wb.sheetNames();
        for (int i = 0; i < names.size(); i++) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new IOException("the read was stopped");
            }
            try (SheetRows rows = wb.rows(names.get(i))) {
                out.add(inspect(names.get(i), i, rows, sampleRows));
            }
        }
        return out;
    }

    static Layout inspect(String name, int index, SheetRows rows, int sampleRows) throws IOException {
        SheetRow header = rows.next();
        while (header != null && header.blank()) {
            header = rows.next();
        }
        if (header == null) {
            return problem(name, index, "the sheet is empty");
        }
        int first = -1;
        int last = -1;
        List<String> names = new ArrayList<>();
        for (int c = 0; c < header.cells().size(); c++) {
            Object v = header.cell(c);
            boolean empty = v == null || (v instanceof String s && s.isBlank());
            if (empty) {
                continue;
            }
            if (!(v instanceof String s)) {
                return problem(name, index, "its first row holds numbers or dates, not column names");
            }
            if (s.strip().length() > 200) {
                return problem(name, index, "its first row holds long text, not column names");
            }
            if (first < 0) {
                first = c;
            } else if (c != last + 1) {
                return problem(name, index, "its first row has gaps between names, so it does not look like a "
                    + "table header");
            }
            last = c;
            names.add(s.strip());
        }
        if (names.size() < 2) {
            return problem(name, index, "its first row has fewer than two column names, so it does not look like a "
                + "table (notes or a single list are not read as tables)");
        }
        int width = last - first + 1;
        SheetTypes[] types = new SheetTypes[width];
        for (int i = 0; i < width; i++) {
            types[i] = new SheetTypes();
        }
        int data = 0;
        int wider = 0;
        SheetRow row;
        while (data < sampleRows && (row = rows.next()) != null) {
            if (row.blank()) {
                continue;
            }
            data++;
            for (int c = 0; c < row.cells().size(); c++) {
                Object v = row.cell(c);
                if (v == null) {
                    continue;
                }
                if (c < first || c > last) {
                    if (!(v instanceof String s && s.isBlank())) {
                        wider++;
                        break;
                    }
                    continue;
                }
                types[c - first].offer(v);
            }
        }
        if (data == 0) {
            return problem(name, index, "it has a header row but no rows under it");
        }
        if (wider * 10 > data) {
            return problem(name, index, "many of its rows have values beyond the header's columns, so it does not "
                + "look like one table");
        }
        List<String> unique = Csv.names(names);
        List<FileColumn> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            columns.add(types[i].column(unique.get(i)));
        }
        return new Layout(name, index, columns, header.number(), first, null);
    }

    private static Layout problem(String name, int index, String reason) {
        return new Layout(name, index, List.of(), 0, 0, reason);
    }

    /** Text the file reader parses back as the column's kind. */
    static String text(Object v) {
        if (v == null || v == ERROR) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) {
                return Long.toString(d.longValue());
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (v instanceof LocalDateTime t) {
            return t.toLocalTime().equals(LocalTime.MIDNIGHT) ? t.toLocalDate().toString() : t.toString();
        }
        return v.toString();
    }

    /** Which kind a sheet column's sampled cells allow. */
    static final class SheetTypes {
        private int values;
        private int bools;
        private int wholes;
        private int numbers;
        private int dates;
        private int dateTimes;

        void offer(Object v) {
            if (v == ERROR || (v instanceof String s && s.isBlank())) {
                return;
            }
            values++;
            if (v instanceof Boolean) {
                bools++;
            } else if (v instanceof Double d) {
                numbers++;
                if (d == Math.rint(d) && Math.abs(d) < 9.0e15) {
                    wholes++;
                }
            } else if (v instanceof LocalDateTime t) {
                if (t.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                    dates++;
                } else {
                    dateTimes++;
                }
            }
        }

        FileColumn column(String name) {
            Kind kind = Kind.TEXT;
            if (values > 0) {
                if (bools == values) {
                    kind = Kind.BOOLEAN;
                } else if (wholes == values) {
                    kind = Kind.INT64;
                } else if (numbers == values) {
                    kind = Kind.FLOAT64;
                } else if (dates == values) {
                    kind = Kind.DATE;
                } else if (dates + dateTimes == values) {
                    kind = Kind.TIMESTAMP;
                }
            }
            return FileColumn.inferred(name, kind, 0);
        }
    }

    /** 0-based column of a cell reference such as {@code AB12}; -1 when there is none. */
    static int column(String ref) {
        if (ref == null) {
            return -1;
        }
        int c = 0;
        int i = 0;
        for (; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            if (c > 16_384) {
                return -1; // past Excel's last column
            }
        }
        return i == 0 ? -1 : c - 1;
    }

    /** .xlsx and .xlsm: the streaming reader. */
    static final class Xssf implements Workbook {
        private final OPCPackage pkg;
        private final XSSFReader reader;
        private final ReadOnlySharedStringsTable strings;
        private final StylesTable styles;
        private final boolean date1904;
        private final List<String> names = new ArrayList<>();
        private final List<PackagePart> parts = new ArrayList<>();
        private final XMLInputFactory xml = XMLHelper.newXMLInputFactory();

        Xssf(Path file) throws IOException {
            try {
                pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
            } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException | RuntimeException e) {
                throw new IOException("it is not a valid Excel workbook (" + e.getMessage() + ")", e);
            }
            try {
                reader = new XSSFReader(pkg);
                List<PackagePart> sst = pkg.getPartsByContentType(XSSFRelation.SHARED_STRINGS.getContentType());
                strings = sst.isEmpty() ? null : new ReadOnlySharedStringsTable(OfficeParts.limited(
                    sst.get(0).getInputStream(), DocumentRuntime.limits().maxPartBytes(), "its shared strings"), false);
                styles = reader.getStylesTable();
                date1904 = date1904(reader);
                XSSFReader.SheetIterator it = reader.getSheetIterator();
                List<String> raw = new ArrayList<>();
                while (it.hasNext() && raw.size() < 10_000) {
                    InputStream sheet = it.next();
                    sheet.close(); // only its name and part are needed here
                    raw.add(it.getSheetName());
                    parts.add(it.getSheetPart());
                }
                names.addAll(safeNames(raw));
            } catch (IOException e) {
                pkg.revert();
                throw e;
            } catch (Exception e) {
                pkg.revert();
                throw new IOException(e.getMessage(), e);
            }
        }

        private boolean date1904(XSSFReader r) throws Exception {
            try (InputStream in = r.getWorkbookData()) {
                XMLStreamReader x = xml.createXMLStreamReader(in);
                try {
                    while (x.hasNext()) {
                        if (x.next() == XMLStreamConstants.START_ELEMENT && x.getLocalName().equals("workbookPr")) {
                            String v = x.getAttributeValue(null, "date1904");
                            return "1".equals(v) || "true".equalsIgnoreCase(v);
                        }
                    }
                } finally {
                    x.close();
                }
            }
            return false;
        }

        @Override
        public List<String> sheetNames() {
            return names;
        }

        @Override
        public SheetRows rows(String sheet) throws IOException {
            int i = names.indexOf(sheet);
            if (i < 0) {
                throw new IOException("the workbook has no sheet called '" + sheet + "' any more: scan the source again");
            }
            InputStream in = parts.get(i).getInputStream();
            try {
                return new XssfRows(xml.createXMLStreamReader(in), in);
            } catch (XMLStreamException e) {
                in.close();
                throw new IOException("sheet '" + sheet + "' is not valid XML (" + e.getMessage() + ")", e);
            }
        }

        @Override
        public void close() {
            pkg.revert(); // read only: nothing to save
        }

        /** One sheet's rows, pulled from its XML. */
        private final class XssfRows implements SheetRows {
            private final XMLStreamReader x;
            private final InputStream in;
            private int lastRow;

            XssfRows(XMLStreamReader x, InputStream in) {
                this.x = x;
                this.in = in;
            }

            @Override
            public SheetRow next() throws IOException {
                try {
                    while (x.hasNext()) {
                        if (x.next() == XMLStreamConstants.START_ELEMENT && x.getLocalName().equals("row")) {
                            String r = x.getAttributeValue(null, "r");
                            int number = lastRow + 1;
                            if (r != null) {
                                try {
                                    number = Integer.parseInt(r.strip());
                                } catch (NumberFormatException e) {
                                    // keep counting
                                }
                            }
                            lastRow = number;
                            return new SheetRow(number, cells());
                        }
                    }
                    return null;
                } catch (XMLStreamException e) {
                    throw new IOException("a sheet is not valid XML (" + e.getMessage() + ")", e);
                }
            }

            /** The cells of the row the reader is on, up to its end tag. */
            private List<Object> cells() throws XMLStreamException, IOException {
                List<Object> cells = new ArrayList<>();
                int next = 0;
                int seen = 0;
                while (x.hasNext()) {
                    int ev = x.next();
                    if (ev == XMLStreamConstants.END_ELEMENT && x.getLocalName().equals("row")) {
                        return cells;
                    }
                    if (ev == XMLStreamConstants.START_ELEMENT && x.getLocalName().equals("c")) {
                        int col = column(x.getAttributeValue(null, "r"));
                        if (col < 0) {
                            col = next;
                        }
                        if (col >= MAX_COLUMNS || ++seen > MAX_COLUMNS) {
                            throw new IOException("a row has more than " + String.format("%,d", MAX_COLUMNS)
                                + " cells, more than Excel allows, so Kilnmark stopped reading it");
                        }
                        next = col + 1;
                        Object v = cell(x.getAttributeValue(null, "t"), x.getAttributeValue(null, "s"));
                        if (v != null) {
                            while (cells.size() < col) {
                                cells.add(null);
                            }
                            if (cells.size() == col) {
                                cells.add(v);
                            } else {
                                cells.set(col, v);
                            }
                        }
                    }
                }
                return cells;
            }

            /** Reads one {@code <c>} element to its end and returns its value. */
            private Object cell(String type, String style) throws XMLStreamException {
                StringBuilder value = null;
                StringBuilder inline = null;
                int depth = 1;
                String in = null;
                while (depth > 0 && x.hasNext()) {
                    int ev = x.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        in = x.getLocalName();
                        if (in.equals("v") && value == null) {
                            value = new StringBuilder();
                        } else if (in.equals("t") && inline == null) {
                            inline = new StringBuilder();
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                        in = null;
                    } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && in != null) {
                        if (in.equals("v") && value != null && value.length() < 1 << 20) {
                            value.append(x.getText());
                        } else if (in.equals("t") && inline != null && inline.length() < 1 << 20) {
                            inline.append(x.getText());
                        }
                    }
                }
                String t = type == null ? "n" : type;
                switch (t) {
                    case "inlineStr":
                        return inline == null ? null : inline.toString();
                    case "s":
                        if (value == null) {
                            return null;
                        }
                        try {
                            return strings == null ? ERROR
                                : strings.getItemAt(Integer.parseInt(value.toString().strip())).getString();
                        } catch (RuntimeException e) {
                            return ERROR;
                        }
                    case "str":
                        return value == null ? null : value.toString();
                    case "b":
                        return value == null ? null : Boolean.valueOf("1".equals(value.toString().strip())
                            || "true".equalsIgnoreCase(value.toString().strip()));
                    case "e":
                        return ERROR;
                    case "d":
                        if (value == null) {
                            return null;
                        }
                        try {
                            String s = value.toString().strip();
                            return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
                        } catch (RuntimeException e) {
                            return value.toString();
                        }
                    default:
                        if (value == null || value.isEmpty()) {
                            return null;
                        }
                        double d;
                        try {
                            d = Double.parseDouble(value.toString().strip());
                        } catch (NumberFormatException e) {
                            return value.toString();
                        }
                        if (style != null && isDate(style) && DateUtil.isValidExcelDate(d)) {
                            return DateUtil.getLocalDateTime(d, date1904, true);
                        }
                        return d;
                }
            }

            private boolean isDate(String style) {
                try {
                    XSSFCellStyle s = styles.getStyleAt(Integer.parseInt(style.strip()));
                    return s != null && DateUtil.isADateFormat(s.getDataFormat(), s.getDataFormatString());
                } catch (RuntimeException e) {
                    return false;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    x.close();
                } catch (XMLStreamException e) {
                    // closing
                }
                in.close();
            }
        }
    }

    /** Legacy .xls, read whole with HSSF (size-limited by the caller). */
    static final class Hssf implements Workbook {
        private final POIFSFileSystem fs;
        private final HSSFWorkbook wb;

        Hssf(Path file) throws IOException {
            try {
                fs = new POIFSFileSystem(file.toFile(), true);
            } catch (RuntimeException e) {
                throw new IOException("it is not a valid Excel 97-2003 workbook (" + e.getMessage() + ")", e);
            }
            try {
                wb = new HSSFWorkbook(fs.getRoot(), false);
            } catch (IOException | RuntimeException e) {
                fs.close();
                throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
        }

        @Override
        public List<String> sheetNames() {
            List<String> raw = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                raw.add(wb.getSheetName(i));
            }
            return safeNames(raw);
        }

        @Override
        public SheetRows rows(String sheet) throws IOException {
            int i = sheetNames().indexOf(sheet);
            Sheet s = i < 0 ? null : wb.getSheetAt(i);
            if (s == null) {
                throw new IOException("the workbook has no sheet called '" + sheet + "' any more: scan the source again");
            }
            Iterator<Row> it = s.rowIterator();
            return new SheetRows() {
                @Override
                public SheetRow next() {
                    if (!it.hasNext()) {
                        return null;
                    }
                    Row r = it.next();
                    List<Object> cells = new ArrayList<>();
                    for (Cell c : r) {
                        int col = c.getColumnIndex();
                        Object v = value(c);
                        if (v == null) {
                            continue;
                        }
                        while (cells.size() < col) {
                            cells.add(null);
                        }
                        cells.add(v);
                    }
                    return new SheetRow(r.getRowNum() + 1, cells);
                }

                @Override
                public void close() {
                    // the workbook holds the sheet
                }
            };
        }

        private static Object value(Cell c) {
            CellType t = c.getCellType();
            if (t == CellType.FORMULA) {
                t = c.getCachedFormulaResultType();
            }
            return switch (t) {
                case STRING -> c.getStringCellValue();
                case BOOLEAN -> c.getBooleanCellValue();
                case ERROR -> ERROR;
                case NUMERIC -> DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue() : c.getNumericCellValue();
                default -> null;
            };
        }

        @Override
        public void close() throws IOException {
            wb.close();
            fs.close();
        }
    }
}
