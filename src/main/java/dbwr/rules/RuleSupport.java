/*******************************************************************************
 * Copyright (c) 2019 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the LICENSE
 * which accompanies this distribution
 ******************************************************************************/
package dbwr.rules;

import static dbwr.WebDisplayRepresentation.logger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.w3c.dom.Element;

import dbwr.macros.MacroProvider;
import dbwr.macros.MacroUtil;
import dbwr.parser.XMLUtil;
import dbwr.widgets.Widget;

/** Rule support
 *
 *  Example from a Rectangle widget:
 *  <pre>
 *  &lt;rule name="Color" prop_id="background_color" out_exp="false">
 *    &lt;exp bool_exp="pv0&gt;2">
 *      &lt;value>
 *        &lt;color name="OK" red="0" green="255" blue="0" />
 *      &lt;/value>
 *    &lt;/exp>
 *    &lt;pv_name>sim://ramp&lt;/pv_name>
 *  &lt;/rule>
 *  </pre>
 *
 *  Rectangle widget invokes RuleSupport to handle rules for "background_color".
 *  RuleSupport then finds the rule and creates JavaScript that's added to
 *  a '&lt;script>' tag at the end of the display HTML.
 */
public class RuleSupport
{
    private final AtomicInteger id = new AtomicInteger();
    private final StringBuilder scripts = new StringBuilder();

    @FunctionalInterface
    private interface ValueParser
    {
        String parse(MacroProvider macros, Element exp) throws Exception;
    }

    /** Create client-side JavaScript for rule
     *  @param macros Macro provider, typically parent widget
     *  @param xml XML for this widget
     *  @param widget Currently handled widget
     *  @param property Property for which to convert rules
     *  @param value_parser Parser that turns rule expression into value for the property
     *  @param default_value Default value of property
     *  @param value_format Formatter for property's value
     *  @param update_code Javascript code to call to update the property with the rule-based value
     *  @throws Exception
     */
    private void handleRule(final MacroProvider macros, final Element xml,
                            final Widget widget, final String property,
                            final ValueParser value_parser,
                            final String default_value,
                            final Function<String, String> value_format,
                            final String update_code) throws Exception
    {
        final Element rules = XMLUtil.getChildElement(xml, "rules");
        if (rules == null)
            return;

        for (final Element re : XMLUtil.getChildElements(rules, "rule"))
        {
            if (! property.equals(re.getAttribute("prop_id")))
                continue;

            if (Boolean.parseBoolean(re.getAttribute("out_exp")))
                throw new Exception("Can only handle plain rules, not 'out_exp' types");

            // Collect PVs,..
            final List<String> pvs = new ArrayList<>();
            for (final Element e : XMLUtil.getChildElements(re, "pv_name"))
                pvs.add(MacroUtil.expand(macros, XMLUtil.getString(e)));
            // Legacy PV names
            for (final Element e : XMLUtil.getChildElements(re, "pv"))
                pvs.add(MacroUtil.expand(macros, XMLUtil.getString(e)));

            // Expressions, values
            final List<String> expr = new ArrayList<>();
            final List<String> values = new ArrayList<>();
            for (final Element e : XMLUtil.getChildElements(re, "exp"))
            {
                // TODO Better expression check/convert
                expr.add(convertExp(MacroUtil.expand(macros, e.getAttribute("bool_exp"))));
                values.add(value_parser.parse(macros, e));
            }

            // Created <script>:
            // let rule1 = new WidgetRule('w9180', 'property', ['sim://ramp', 'sim://sine' ]);
            // rule1.eval = function()
            // {
            //   let pv0 = this.value['sim://ramp'];
            //   let pv1 = this.value['sim://sine'];
            //   if (pv0>2) return 24;
            //   return 42;
            // }
            // rule1.update = set_svg_background_color
            final String rule = "rule" + id.incrementAndGet();

            // Create script for this rule
            final StringBuilder buf = new StringBuilder();
            buf.append("// Rule '").append(re.getAttribute("name")).append("'\n");
            buf.append("let " + rule +
                           " = new WidgetRule('" + widget.getWID() + "', '" + property + "', [" +
                           pvs.stream().map(pv -> "'" + pv + "'").collect(Collectors.joining(",")) +
                           "]);\n");
            buf.append(rule + ".eval = function()\n");
            buf.append("{\n");

            int N = pvs.size();
            for (int i=0; i<N; ++i)
            {
                buf.append("  let pv" + i + " = this.value['" + pvs.get(i) + "'];\n");
                buf.append("  let pvStr" + i + " = this.valueStr['" + pvs.get(i) + "'];\n");
            }

            N = expr.size();
            for (int i=0; i<N; ++i)
                buf.append("  if (" + expr.get(i) + ") return " + value_format.apply(values.get(i)) + ";\n");
            buf.append("  return " + value_format.apply(default_value) + ";\n");

            buf.append("}\n");
            buf.append(rule + ".update = " + update_code + "\n");

            final String script = buf.toString();
            logger.log(Level.INFO,
                       widget + " rule:\n" +
                       XMLUtil.toString(re) +
                       script);

            scripts.append(buf.toString());
        }
    }

    /** @param exp Jython expression from rule
     *  @return JavaScript expression
     */
    private String convertExp(final String exp)
    {
        // Instead of 'pvInt0' for integer value
        // use plain 'pv0' value.
        // Map python 'and', 'or', 'not' to Javascript
        return exp.replace("pvInt", "pv")
                  .replace("and", " && ")
                  .replace("or", " || ")
                  .replace("not", " ! ");
    }

    public void handleNumericRule(final MacroProvider macros, final Element xml,
            final Widget widget, final String property, final double default_value,
            final String update_code) throws Exception
    {
        handleRule(macros, xml, widget, property,
                   (mac, exp) -> XMLUtil.getChildString(mac, exp, "value").orElseThrow(() -> new Exception("Missing value")),
                   Double.toString(default_value),
                   text -> text, update_code);
    }

    public void handleColorRule(final MacroProvider macros, final Element xml,
                                final Widget widget, final String property, final String default_color,
                                final String update_code) throws Exception
    {
        handleRule(macros, xml, widget, property,
                   (mac, exp) -> XMLUtil.getColor(exp, "value").orElseThrow(() -> new Exception("Missing color")),
                   default_color,
                   color_text -> "'" + color_text + "'", update_code);
    }

    /** Check if there is a rule for the visibility
     *
     *  @param macros Macro provider, usually the parent widget
     *  @param xml XML for this widget
     *  @param widget Widget where rule might need to be added
     *  @param default_visibility Original value for the visibility
     *  @throws Exception on error
     */
    public void handleVisibilityRule(final MacroProvider macros, final Element xml,
                                     final Widget widget, final boolean default_visibility) throws Exception
    {
        handleRule(macros, xml, widget, "visible",
                (mac, exp) -> Boolean.toString(XMLUtil.getChildBoolean(exp, "value").orElseThrow(() -> new Exception("Missing true/false value, got '" + exp + "'"))),
                Boolean.toString(default_visibility),
                truefalse -> truefalse, "set_visibility");
    }

    public void addScripts(final PrintWriter html)
    {
        html.println("<script>");
        html.println(scripts.toString());
        html.println("</script>");
    }
}
