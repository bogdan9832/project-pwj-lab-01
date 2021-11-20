package demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.ValidationException;
import javax.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api/tasks")
public class TaskController {
    @Autowired
    private TaskService service;

    @Autowired
    private Validator validator;

    @Operation(summary = "Search tasks", operationId = "getTasks")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found tasks",
                    content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Object[].class))}
            ),
            @ApiResponse(responseCode = "204", description = "No tasks found")
    })
    @GetMapping
    public ResponseEntity<List<Map<String,Object>>> getTasks (@RequestParam(required = false) String title,
                                                  @RequestParam(required = false) String description,
                                                  @RequestParam(required = false) String assignedTo,
                                                  @RequestParam(required = false) TaskModel.TaskStatus status,
                                                  @RequestParam(required = false) TaskModel.TaskSeverity severity,
                                                  @RequestHeader(required = false, name="X-Fields") String fields,
                                                  @RequestHeader(required = false, name="X-Sort") String sort) {
        List<TaskModel> tasks = service.getTasks(title, description, assignedTo, status, severity);
        if(tasks.isEmpty()) {
            return ResponseEntity.noContent().build();
        } else {

            return ResponseEntity.ok(getFilteredTasks(fields, sort, tasks));
        }
    }
    @Operation(summary = "Export tasks ", operationId = "exportTasks")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tasks in csv format",
                    content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
                            schema = @Schema(implementation = Object.class))}
            ),
            @ApiResponse(responseCode = "200", description = "Tasks in xml format",
                    content = {@Content(mediaType = MediaType.APPLICATION_XML_VALUE,
                            schema = @Schema(implementation = Object.class))}
            )
    })
    @GetMapping("/export")
    public ResponseEntity<Resource> exportTasks (@RequestParam(required = false) String title,
                                                 @RequestParam(required = false) String description,
                                                 @RequestParam(required = false) String assignedTo,
                                                 @RequestParam(required = false) TaskModel.TaskStatus status,
                                                 @RequestParam(required = false) TaskModel.TaskSeverity severity,
                                                 @RequestHeader(required = false, name="Fields") String fields,
                                                 @RequestHeader(required = false, name="Sort") String sort,
                                                 @RequestHeader(required = false) String format) throws JsonProcessingException {
        if(format == null || !format.equals("xml")){
            format = "xml";
        }


        List<TaskModel> tasks = service.getTasks(title, description, assignedTo, status, severity);
        var items = getFilteredTasks(fields, sort, tasks);
        return getFormated(items,format);

    }

    private ResponseEntity<Resource> getFormated(List<Map<String, Object>> items, String format) throws JsonProcessingException {
        ByteArrayInputStream byteArrayOutputStream = format.equals("csv") ? getCsv(items) : getXml(items);


        InputStreamResource fileInputStream = new InputStreamResource(byteArrayOutputStream);


        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tasks." + format);
        headers.set(HttpHeaders.CONTENT_TYPE, "text/" + format);
        return new ResponseEntity<>(
                fileInputStream,
                headers,
                HttpStatus.OK
        );
    }

    private List<Map<String,Object>> getFilteredTasks(@RequestHeader(required = false, name = "Fields") String fields, @RequestHeader(required = false, name = "Sort") String sort, List<TaskModel> tasks) {
        if(sort != null && !sort.isBlank()) {
            tasks = tasks.stream().sorted((first, second) -> BaseModel.sorter(sort).compare(first, second)).collect(Collectors.toList());
        }
        List<Map<String,Object>> items;
        if(fields == null || fields.isBlank()) {

            fields = Arrays.stream(TaskModel.class.getDeclaredFields()).map(Field::getName).collect(Collectors.joining(","));
        }
        String finalFields = fields;
        items = tasks.stream().map(task -> task.sparseFields(finalFields.split(","))).collect(Collectors.toList());

        return items;
    }

    private ByteArrayInputStream getCsv(List<Map<String,Object>> items) {
        ByteArrayInputStream byteArrayOutputStream;
        try (
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // defining the CSV printer
                CSVPrinter csvPrinter = new CSVPrinter(
                        new PrintWriter(out),

                        CSVFormat.DEFAULT

                );
        ) {
            // populating the CSV content
            for (var record : items)
                csvPrinter.printRecord(record.values());

            // writing the underlying stream
            csvPrinter.flush();

            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }


    }

    private ByteArrayInputStream getXml(List<Map<String,Object>> items) throws JsonProcessingException {
        XmlMapper xmlMapper = new XmlMapper();
        var data = xmlMapper.writeValueAsBytes(items);
        return new ByteArrayInputStream(data);
    }


    @Operation(summary = "Get a task", operationId = "getTask")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found task",
                    content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Object.class))}
            ),
            @ApiResponse(responseCode = "404", description = "No task found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<Object> getTaskById(@PathVariable String id, @RequestHeader(required = false, name="X-Fields") String fields) {
        Optional<TaskModel> task = service.getTask(id);
        if(task.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            if(fields != null && !fields.isBlank()) {
                return ResponseEntity.ok(task.get().sparseFields(fields.split(",")));
            } else {
              return ResponseEntity.ok(task.get());
            }
        }
    }

    @Operation(summary = "Create a task", operationId = "addTask")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Task was created",
                   headers={@Header(name ="location", schema = @Schema(type = "String"))}
            ),
            @ApiResponse(responseCode = "500", description = "Something went wrong"),
            @ApiResponse(responseCode = "204", description = "Bulk tasks created")
    })
    @PostMapping
    public ResponseEntity<Void> addTask(@RequestBody String payload, @RequestHeader(required = false, name="X-Action") String action) throws ValidationException, IOException {
       // try {
            if("bulk".equals(action)) {
                TaskModel[] tasks = new ObjectMapper().readValue(payload, TaskModel[].class);
                validate(tasks);
                for(TaskModel taskModel : tasks) {
                    service.addTask(taskModel);
                }
                return ResponseEntity.noContent().build();
            } else {
                TaskModel task = new ObjectMapper().readValue(payload, TaskModel.class);
                validate(new Object[]{task});
                TaskModel taskModel = service.addTask(task);
                URI uri = WebMvcLinkBuilder.linkTo(getClass()).slash(taskModel.getId()).toUri();
                return ResponseEntity.created(uri).build();
            }
//        } catch (IOException e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
//        }
    }

    @Operation(summary = "Update a task", operationId = "updateTask")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task was updated"),
            @ApiResponse(responseCode = "500", description = "Something went wrong"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @PutMapping("/{id}")
    public ResponseEntity<Void> updateTask(@PathVariable String id, @Valid @RequestBody TaskModel task) throws IOException {
      //  try {
            if (service.updateTask(id, task)) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
//        } catch (IOException e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
//        }
    }

    @Operation(summary = "Patch a task", operationId = "patchTask")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task was patched"),
            @ApiResponse(responseCode = "500", description = "Something went wrong"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @PatchMapping("/{id}")
    public ResponseEntity<Void> patchTask(@PathVariable String id, @Valid @RequestBody TaskModel task) throws IOException {
   //     try {
            if (service.patchTask(id, task)) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
//        } catch (IOException e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
//        }
    }

    @Operation(summary = "Delete a task", operationId = "deleteTask")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task was deleted"),
            @ApiResponse(responseCode = "500", description = "Something went wrong"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable String id) throws IOException {
     //   try {
            if (service.deleteTask(id)) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
//        } catch (IOException e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
//        }
    }

    @Operation(summary = "Check a task", operationId = "checkTask")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task was found"),
            @ApiResponse(responseCode = "404", description = "Task was not found")
    })
    @RequestMapping(method = RequestMethod.HEAD, value ="/{id}")
    public ResponseEntity checkTask(@PathVariable String id) {
        Optional<TaskModel> taskModel = service.getTask(id);
        return taskModel.isPresent() ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private void validate(Object[] objects) throws ValidationException {
      String message =  Arrays.stream(objects)
                .map(o -> validator.validate(o).stream()
                        .map(objectConstraintViolation -> objectConstraintViolation.getMessage())
                        .filter(error -> !error.isBlank())
                        .collect(Collectors.joining("|"))
                ).collect(Collectors.joining("|"));
        if(!message.isBlank()) {
            throw new ValidationException(message);
        }
    }
}
