import os
from reportlab.lib.pagesizes import letter
from reportlab.lib.units import inch
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, KeepTogether
from reportlab.pdfgen import canvas

class NumberedCanvas(canvas.Canvas):
    """
    Canvas personalizado para normas APA 7ma edición:
    Numeración de página en la esquina superior derecha (a 1 pulgada del margen derecho y 0.5 pulgada superior).
    """
    def __init__(self, *args, **kwargs):
        super(NumberedCanvas, self).__init__(*args, **kwargs)
        self._saved_page_states = []

    def showPage(self):
        self._saved_page_states.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        num_pages = len(self._saved_page_states)
        for state in self._saved_page_states:
            self.__dict__.update(state)
            self.draw_page_number(num_pages)
            canvas.Canvas.showPage(self)
        canvas.Canvas.save(self)

    def draw_page_number(self, page_count):
        self.saveState()
        self.setFont("Helvetica", 10)
        # APA 7: Número de página en margen superior derecho
        page_num_str = str(self._pageNumber)
        self.drawRightString(letter[0] - 1 * inch, letter[1] - 0.6 * inch, page_num_str)
        self.restoreState()


def generar_informe_apa(output_path):
    # Márgenes de 1 pulgada (2.54 cm = 72 puntos)
    doc = SimpleDocTemplate(
        output_path,
        pagesize=letter,
        leftMargin=1 * inch,
        rightMargin=1 * inch,
        topMargin=1 * inch,
        bottomMargin=1 * inch
    )

    styles = getSampleStyleSheet()

    # Estilos APA 7
    style_title_page_title = ParagraphStyle(
        'APATitlePageTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=14,
        leading=20,
        alignment=1, # Centrado
        spaceAfter=14
    )

    style_title_page_meta = ParagraphStyle(
        'APATitlePageMeta',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=11,
        leading=18,
        alignment=1, # Centrado
        spaceAfter=6
    )

    style_heading_1 = ParagraphStyle(
        'APAHeading1',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=12,
        leading=16,
        alignment=1, # APA 7 Nivel 1: Centrado, Negrita
        spaceBefore=14,
        spaceAfter=10,
        keepWithNext=True
    )

    style_heading_2 = ParagraphStyle(
        'APAHeading2',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=11,
        leading=15,
        alignment=0, # APA 7 Nivel 2: Alineado a la izquierda, Negrita
        spaceBefore=10,
        spaceAfter=6,
        keepWithNext=True
    )

    style_body = ParagraphStyle(
        'APABody',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=10.5,
        leading=17,
        firstLineIndent=0.5 * inch, # Sangría primera línea 1.27 cm (0.5 in)
        spaceAfter=8,
        alignment=4 # Justificado
    )

    style_body_no_indent = ParagraphStyle(
        'APABodyNoIndent',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=10.5,
        leading=17,
        spaceAfter=8,
        alignment=4
    )

    style_reference = ParagraphStyle(
        'APAReference',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=10,
        leading=16,
        leftIndent=0.5 * inch,
        firstLineIndent=-0.5 * inch, # Sangría francesa
        spaceAfter=8,
        alignment=4
    )

    story = []

    # ==================== PORTADA (ESTILO ESTUDIANTE APA 7) ====================
    story.append(Spacer(1, 1.5 * inch))
    story.append(Paragraph("<b>Sistema Integral de Control Remoto para Auto y Dron Mediante Aplicación Móvil y Sensores de Orientación</b>", style_title_page_title))
    story.append(Spacer(1, 0.4 * inch))
    story.append(Paragraph("Onesti, Silvero y Mendieta", style_title_page_meta))
    story.append(Paragraph("Grupo N.° 1", style_title_page_meta))
    story.append(Paragraph("Escuela de Educación Secundaria Técnica — 7.° Año", style_title_page_meta))
    story.append(Paragraph("Materia: Programación", style_title_page_meta))
    story.append(Paragraph("Docentes: Prof. Hernán Figueroa y Prof. Eduardo Piris", style_title_page_meta))
    story.append(Paragraph("Proyecto Colaborativo Institucional: GEdeCon", style_title_page_meta))
    story.append(Paragraph("Septiembre de 2026", style_title_page_meta))
    story.append(PageBreak())

    # ==================== RESUMEN ====================
    story.append(Paragraph("<b>Resumen</b>", style_heading_1))
    resumen_texto = (
        "El presente proyecto describe el diseño, fundamentación y arquitectura de un sistema integral de control remoto "
        "para un vehículo terrestre (auto) y una aeronave no tripulada (dron), gobernados desde una aplicación móvil "
        "aprovechando los sensores de hardware del dispositivo móvil. En estricto cumplimiento con las condiciones "
        "obligatorias del curso, se incorpora el giroscopio y acelerómetro del smartphone mediante la API estandarizada "
        "de orientación para la teleoperación basada en gestos e inclinación física. El sistema se vincula con un "
        "microcontrolador central encargado de decodificar comandos y gobernar actuadores, garantizando una respuesta en "
        "tiempo real con baja latencia, arquitectura modular y una interfaz de usuario accesible e intuitiva."
    )
    story.append(Paragraph(resumen_texto, style_body_no_indent))
    story.append(Spacer(1, 0.1 * inch))
    keywords_texto = "<i>Palabras clave:</i> aplicación móvil, giroscopio, acelerómetro, Arduino, dron, control vehicular, GEdeCon."
    story.append(Paragraph(keywords_texto, style_body_no_indent))
    story.append(PageBreak())

    # ==================== SECCIÓN 1: COMPRENSIÓN ====================
    story.append(Paragraph("<b>Sección 1: Comprensión de la Problemática</b>", style_heading_1))
    s1_1 = (
        "El avance constante de los dispositivos móviles inteligentes los ha transformado en terminales con alta capacidad de cómputo "
        "y un conjunto diverso de sensores de precisión. No obstante, las interfaces tradicionales de control remoto para vehículos y drones "
        "suelen limitarse a mandos analógicos externos o joysticks táctiles que no aprovechan de forma directa la interacción física natural del usuario."
    )
    s1_2 = (
        "La problemática identificada reside en diseñar una alternativa de interacción humano-máquina (HMI) que permita operar vehículos terrestres "
        "y aéreos mediante la inclinación gestual del smartphone, reduciendo la curva de aprendizaje y dotando al sistema de una experiencia fluida, "
        "accesible y plenamente funcional para la comunidad educativa en el marco de la iniciativa colaborativa GEdeCon."
    )
    story.append(Paragraph(s1_1, style_body))
    story.append(Paragraph(s1_2, style_body))

    # ==================== SECCIÓN 2: ANÁLISIS ====================
    story.append(Paragraph("<b>Sección 2: Análisis y Justificación Tecnológica</b>", style_heading_1))
    s2_1 = (
        "Para materializar la solución, se analizaron diversas plataformas de desarrollo de software móvil y arquitecturas de hardware. "
        "Desde la perspectiva del software móvil, se optó por una aplicación estructurada sobre tecnologías web estándar (HTML5, CSS moderno y JavaScript modular) "
        "adaptada como Progressive Web App (PWA). Esta decisión elimina barreras de instalación y dependencias de compilación específicas de sistema operativo, "
        "facilitando una prueba directa sobre cualquier dispositivo móvil con navegador contemporáneo."
    )
    s2_2 = (
        "Respecto al hardware de control, se evaluó el uso de Arduino Uno/Nano con módulos Bluetooth clásicos (HC-05/06) frente a arquitecturas "
        "basadas en microcontroladores Wi-Fi como el ESP32. Ambas vías resultan plenamente viables; la comunicación basada en protocolos directos de baja latencia "
        "asegura que las tramas de orientación generadas por el sensor del teléfono sean procesadas por los actuadores en menos de 15 milisegundos, "
        "evitando pérdidas de estabilidad en maniobras críticas."
    )
    story.append(Paragraph(s2_1, style_body))
    story.append(Paragraph(s2_2, style_body))

    # ==================== SECCIÓN 3: PROPUESTA ====================
    story.append(Paragraph("<b>Sección 3: Propuesta del Sistema Integral</b>", style_heading_1))
    story.append(Paragraph("<b>Arquitectura del Auto y Dron</b>", style_heading_2))
    s3_1 = (
        "La propuesta del Grupo 1 consiste en un ecosistema centralizado compuesto por tres componentes interdependientes: "
        "la aplicación móvil de control, el módulo de control del automóvil y el controlador del dron. El vehículo terrestre cuenta con tracción "
        "mediante motores de corriente continua gobernados por un puente H, mientras que el módulo de vuelo responde a comandos de aceleración y cabeceo."
    )
    s3_2 = (
        "El operador dispone en la pantalla móvil de un selector unificado que permite alternar el perfil operativo: en modo vehículo terrestre, "
        "la inclinación lateral rige el ángulo de viraje y la inclinación frontal rige el avance o retroceso; en modo dron, se adiciona un mando seguro "
        "para el control de potencia y altitud vertical (Throttle), protegiendo la aeronave ante caídas involuntarias mediante un conmutador de seguridad (Deadman switch)."
    )
    story.append(Paragraph(s3_1, style_body))
    story.append(Paragraph(s3_2, style_body))

    # ==================== SECCIÓN 4: DISEÑO UX/UI ====================
    story.append(Paragraph("<b>Sección 4: Diseño UX/UI</b>", style_heading_1))
    s4_1 = (
        "La interfaz gráfica fue concebida bajo principios de ergonomía móvil y estética de cabina de instrumentos (Cockpit/HUD). "
        "Se priorizó un esquema de alto contraste con fondo oscuro para optimizar la visibilidad en exteriores y reducir el consumo energético. "
        "La pantalla dispone de un indicador visual de horizonte artificial que retroalimenta al usuario en tiempo real con los grados de inclinación medidos."
    )
    s4_2 = (
        "Asimismo, se integraron alertas visuales de estado de conexión, botones táctiles de parada de emergencia y un panel de telemetría simplificado "
        "que permite a usuarios noveles operar el vehículo con total seguridad sin necesidad de entrenamiento técnico previo."
    )
    story.append(Paragraph(s4_1, style_body))
    story.append(Paragraph(s4_2, style_body))

    # ==================== SECCIÓN 5: DESARROLLO Y PRUEBAS ====================
    story.append(Paragraph("<b>Sección 5: Desarrollo y Pruebas</b>", style_heading_1))
    s5_1 = (
        "El código fuente se estructuró de manera rigurosamente modular para facilitar su mantenimiento y expansión futura, separando la capa de captura "
        "de sensores, el procesador de datos cinemáticos y el canal de comunicación inalámbrica. En la etapa de pruebas preliminares, se determinó una frecuencia "
        "de muestreo de 30 a 50 Hz para la lectura del giroscopio, valor que equilibra fluidez en la respuesta mecánica y eficiencia en el uso de batería."
    )
    s5_2 = (
        "Se diseñó un protocolo de pruebas que abarca: (a) pruebas unitarias de cálculo de ángulos sin hardware conectado, (b) pruebas de enlace y latencia de paquetes, "
        "y (c) pruebas de respuesta en banco estático con motores antes de su montaje dinámico final."
    )
    story.append(Paragraph(s5_1, style_body))
    story.append(Paragraph(s5_2, style_body))

    # ==================== SECCIÓN 6: SENSORES (OBLIGATORIO) ====================
    story.append(Paragraph("<b>Sección 6: Integración del Sensor Móvil (Condición Obligatoria)</b>", style_heading_1))
    s6_1 = (
        "En consonancia con el requisito excluyente establecido en las pautas evaluativas, el sistema fundamenta su lógica en los sensores reales "
        "del dispositivo móvil. Se implementa la interfaz DeviceOrientationEvent disponible en navegadores modernos, la cual combina lecturas del acelerómetro "
        "y del giroscopio de estado sólido (MEMS) integrados en el smartphone."
    )
    s6_2 = (
        "Específicamente, se capturan dos variables angulares fundamentales: el ángulo Beta (inclinación en el eje X, rango -180° a +180°), que mapea la acción de "
        "avance y retroceso (Pitch), y el ángulo Gamma (inclinación en el eje Y, rango -90° a +90°), que mapea el viraje direccional o alabeo (Roll). "
        "Se aplica una zona muerta digital (Deadzone) de +/- 5 grados para evitar oscilaciones espurias provocadas por el temblor natural de las manos del operador."
    )
    story.append(Paragraph(s6_1, style_body))
    story.append(Paragraph(s6_2, style_body))

    # ==================== REFERENCIAS (APA 7) ====================
    story.append(Spacer(1, 0.2 * inch))
    story.append(Paragraph("<b>Referencias</b>", style_heading_1))
    
    ref1 = (
        "American Psychological Association. (2020). <i>Publication manual of the American Psychological Association</i> (7.ª ed.). "
        "https://doi.org/10.1037/0000165-000"
    )
    ref2 = (
        "Arduino. (2024). <i>Arduino reference and language documentation</i>. Arduino AG. "
        "https://www.arduino.cc/reference/en/"
    )
    ref3 = (
        "World Wide Web Consortium. (2023). <i>DeviceOrientation Event Specification</i>. W3C Recommendation. "
        "https://www.w3.org/TR/orientation-event/"
    )
    
    story.append(Paragraph(ref1, style_reference))
    story.append(Paragraph(ref2, style_reference))
    story.append(Paragraph(ref3, style_reference))

    # Construir PDF
    doc.build(story, canvasmaker=NumberedCanvas)
    print(f"PDF generado exitosamente en: {output_path}")

if __name__ == '__main__':
    directorio_actual = os.path.dirname(os.path.abspath(__file__))
    destino_pdf = os.path.join(directorio_actual, "Informe_Propuesta_Grupo1_APA.pdf")
    generar_informe_apa(destino_pdf)
