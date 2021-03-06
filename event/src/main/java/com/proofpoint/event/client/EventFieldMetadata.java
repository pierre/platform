package com.proofpoint.event.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.codehaus.jackson.JsonGenerator;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import static com.google.common.base.Objects.firstNonNull;
import static com.proofpoint.event.client.EventDataType.validateFieldValueType;

class EventFieldMetadata
{
    public static final Comparator<EventFieldMetadata> NAME_COMPARATOR = new Comparator<EventFieldMetadata>()
    {
        public int compare(EventFieldMetadata a, EventFieldMetadata b)
        {
            return a.name.compareTo(b.name);
        }
    };

    private final String name;
    private final String v1Name;
    private final Method method;
    private final EventDataType eventDataType;
    private final EventTypeMetadata<?> nestedType;
    private final boolean iterable;

    EventFieldMetadata(String name, String v1Name, Method method, EventDataType eventDataType, EventTypeMetadata<?> nestedType, boolean iterable)
    {
        Preconditions.checkArgument((eventDataType != null) || (nestedType != null), "both eventDataType and nestedType are null");
        Preconditions.checkArgument((eventDataType == null) || (nestedType == null), "both eventDataType and nestedType are set");

        this.name = name;
        this.v1Name = v1Name;
        this.method = method;
        this.eventDataType = eventDataType;
        this.nestedType = nestedType;
        this.iterable = iterable;
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored") // IDEA-74322
    private Object getValue(Object event)
            throws InvalidEventException
    {
        try {
            return method.invoke(event);
        }
        catch (Exception e) {
            throw new InvalidEventException(firstNonNull(e.getCause(), e),
                    "Unable to get value of event field %s: Exception occurred while invoking [%s]", name, method.toGenericString());
        }
    }

    public void writeField(JsonGenerator jsonGenerator, Object event)
            throws IOException
    {
        writeField(jsonGenerator, event, new ArrayDeque<Object>());
    }

    private void writeField(JsonGenerator jsonGenerator, Object event, Deque<Object> objectStack)
            throws IOException
    {
        Object value = getValue(event);
        if (value != null) {
            jsonGenerator.writeFieldName(name);
            if (iterable) {
                validateFieldValueType(value, Iterable.class);
                writeArray(jsonGenerator, (Iterable<?>) value, objectStack);
            }
            else {
                writeFieldValue(jsonGenerator, value, objectStack);
            }
        }
    }

    private void writeFieldValue(JsonGenerator jsonGenerator, Object value, Deque<Object> objectStack)
            throws IOException
    {
        if (eventDataType != null) {
            eventDataType.writeFieldValue(jsonGenerator, value);
        }
        else {
            validateFieldValueType(value, nestedType.getEventClass());
            writeObject(jsonGenerator, value, objectStack);
        }
    }

    private void writeArray(JsonGenerator jsonGenerator, Iterable<?> value, Deque<Object> objectStack)
            throws IOException
    {
        jsonGenerator.writeStartArray();
        for (Object item : value) {
            writeFieldValue(jsonGenerator, item, objectStack);
        }
        jsonGenerator.writeEndArray();
    }

    private void writeObject(JsonGenerator jsonGenerator, Object value, Deque<Object> objectStack)
            throws IOException
    {
        checkForCycles(value, objectStack);
        objectStack.push(value);
        jsonGenerator.writeStartObject();
        for (EventFieldMetadata field : nestedType.getFields()) {
            field.writeField(jsonGenerator, value, objectStack);
        }
        jsonGenerator.writeEndObject();
        objectStack.pop();
    }

    private static void checkForCycles(Object value, Deque<Object> objectStack)
            throws InvalidEventException
    {
        for (Object o : objectStack) {
            if (value == o) {
                List<Object> path = Lists.reverse(Lists.newArrayList(objectStack));
                throw new InvalidEventException("Cycle detected in event data: %s", path);
            }
        }
    }

    public void writeFieldV1(JsonGenerator jsonGenerator, Object event)
            throws IOException
    {
        Preconditions.checkState(!iterable, "iterable fields not supported for JSON V1");
        Preconditions.checkState(nestedType == null, "nested types not supported for JSON V1");
        Object value = getValue(event);
        if (value != null) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("name", v1Name);
            jsonGenerator.writeFieldName("value");
            eventDataType.writeFieldValue(jsonGenerator, value);
            jsonGenerator.writeEndObject();
        }
    }
}
